package com.codecompass.repo;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 克隆编排：URL 校验、命令拼装、受控环境、失败清理。
 *
 * 这里刻意不碰网络 —— 真实克隆放在 @Tag("integration") 的测试里。
 * 失败清理用"指向不存在的 git 可执行文件"来触发，是真实的行为验证而非 mock。
 */
class GitRepositoryClonerTest {

    @TempDir
    Path tempRoot;

    private ScheduledExecutorService scheduler;

    @AfterEach
    void tearDown() {
        if (scheduler != null) {
            scheduler.shutdownNow();
        }
    }

    private CloneProperties properties(String gitExecutable) {
        CloneProperties properties = new CloneProperties();
        properties.setGitExecutable(gitExecutable);
        properties.setTempRoot(tempRoot);
        properties.setTimeout(Duration.ofSeconds(5));
        return properties;
    }

    /** 扫描配置（方案 A：克隆的稀疏模式由它派生）。测试可替换以验证派生关系。 */
    private ScanProperties scan = defaultScan();

    private static ScanProperties defaultScan() {
        ScanProperties.SourceSpec java = new ScanProperties.SourceSpec();
        java.setLanguage("java");
        java.setSourceRoot("src/main/java");
        java.setFileExtensions(List.of(".java"));
        java.setExcludedFileNames(List.of("package-info.java", "module-info.java"));

        ScanProperties scanProperties = new ScanProperties();
        scanProperties.setSources(List.of(java));
        return scanProperties;
    }

    private GitRepositoryCloner cloner(String gitExecutable) {
        return cloner(gitExecutable, properties -> { });
    }

    private GitRepositoryCloner cloner(String gitExecutable, java.util.function.Consumer<CloneProperties> customizer) {
        scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread thread = new Thread(r, "clone-watchdog-test");
            thread.setDaemon(true);
            return thread;
        });
        CloneProperties cloneProperties = properties(gitExecutable);
        customizer.accept(cloneProperties);
        return new GitRepositoryCloner(cloneProperties,
                scan,
                new TempWorkspaceManager(cloneProperties.getTempRoot()),
                new GitProcessRunner(),
                new DiskUsageMeter(),
                scheduler);
    }
    // ---------- URL 校验 ----------

    @Test
    @DisplayName("URL 校验：只接受 https 的 github.com 仓库地址")
    void validatesRepositoryUrl() {
        GitRepositoryCloner cloner = cloner("git");

        assertThat(cloner.validateRepositoryUrl("https://github.com/spring-projects/spring-petclinic"))
                .isEmpty();
        assertThat(cloner.validateRepositoryUrl("https://github.com/spring-projects/spring-petclinic.git"))
                .isEmpty();

        assertThat(cloner.validateRepositoryUrl(null)).isPresent();
        assertThat(cloner.validateRepositoryUrl("  ")).isPresent();
        assertThat(cloner.validateRepositoryUrl("https://github.com/only-owner")).isPresent();
        // 非 https：明文传输，拒绝
        assertThat(cloner.validateRepositoryUrl("http://github.com/a/b")).isPresent();
        // 非 github：任务范围只要求 GitHub
        assertThat(cloner.validateRepositoryUrl("https://gitlab.com/a/b")).isPresent();
        // SSH 会走密钥与交互提示，与"禁用交互"冲突
        assertThat(cloner.validateRepositoryUrl("git@github.com:a/b.git")).isPresent();
        // 本地协议不该被远程输入触发
        assertThat(cloner.validateRepositoryUrl("file:///etc/passwd")).isPresent();
    }

    @Test
    @DisplayName("非法 URL 直接失败，且不留下任何工作区目录")
    void invalidUrlLeavesNothingBehind() {
        GitRepositoryCloner cloner = cloner("git");

        CloneResult result = cloner.clone("ftp://example.com/repo");

        assertThat(result.success()).isFalse();
        assertThat(result.errorMessage()).isNotBlank();
        assertThat(result.localPath()).isNull();
        assertThat(listTempRoot()).isEmpty();
    }

    // ---------- 工作区 id ----------

    @Test
    @DisplayName("由 URL 推导的工作区 id 不含路径分隔符（否则会被路径校验挡下）")
    void workspaceIdIsPathSafe() {
        GitRepositoryCloner cloner = cloner("git");

        String id = cloner.workspaceIdFor("https://github.com/spring-projects/spring-petclinic.git");

        assertThat(id).doesNotContain("/").doesNotContain("\\").isNotEqualTo(".").isNotEqualTo("..");
        assertThat(id).contains("spring-petclinic");
    }

    @Test
    @DisplayName("同一 URL 推导出的 id 稳定（缓存 key 与目录复用都依赖这一点）")
    void workspaceIdIsStable() {
        GitRepositoryCloner cloner = cloner("git");
        String url = "https://github.com/spring-projects/spring-petclinic";

        assertThat(cloner.workspaceIdFor(url)).isEqualTo(cloner.workspaceIdFor(url));
        assertThat(cloner.workspaceIdFor(url + ".git")).isEqualTo(cloner.workspaceIdFor(url));
    }

    // ---------- 命令拼装与受控环境 ----------

    @Test
    @DisplayName("命令序列：浅克隆 + 无 blob + 不检出 → no-cone 稀疏初始化 → 设置路径模式 → checkout")
    void buildsSparseShallowCommandSequence() {
        GitRepositoryCloner cloner = cloner("git");
        Path workspace = tempRoot.resolve("ws");

        List<List<String>> commands = cloner.buildCommands(workspace, "https://github.com/a/b");

        assertThat(commands).hasSize(4);
        assertThat(commands.get(0))
                .containsExactly("git", "-c", "submodule.recurse=false",
                        "clone", "--depth", "1", "--filter=blob:none", "--no-checkout",
                        "https://github.com/a/b", workspace.toString());
        assertThat(commands.get(1)).contains("sparse-checkout", "init", "--no-cone");
        assertThat(commands.get(2))
                .contains("sparse-checkout", "set")
                .contains("pom.xml", "**/pom.xml", "**/src/main/java/**");
        assertThat(commands.get(3)).contains("checkout");
    }

    @Test
    @DisplayName("稀疏检出模式由 scan.source-root 派生：改配置即改检出范围，不会与扫描范围漂移")
    void derivesSparsePatternsFromScanSourceRoot() {
        ScanProperties.SourceSpec python = new ScanProperties.SourceSpec();
        python.setLanguage("python");
        python.setSourceRoot("src/main/python");
        python.setFileExtensions(List.of(".py"));
        ScanProperties pythonScan = new ScanProperties();
        pythonScan.setSources(List.of(python));
        scan = pythonScan;

        List<List<String>> commands =
                cloner("git").buildCommands(tempRoot.resolve("ws"), "https://github.com/a/b");

        assertThat(commands.get(2))
                .as("检出范围必须跟着扫描配置走，否则新增语言时会出现"
                        + "「配了扫描根但文件根本没被检出」的空集合")
                .contains("**/src/main/python/**")
                .doesNotContain("**/src/main/java/**");
        assertThat(commands.get(2)).contains("pom.xml", "**/pom.xml");
    }

    @Test
    @DisplayName("命令里绝不出现 --recurse-submodules（需求明令禁止）")
    void neverRecursesSubmodules() {
        GitRepositoryCloner cloner = cloner("git");

        List<List<String>> commands = cloner.buildCommands(tempRoot.resolve("ws"), "https://github.com/a/b");

        assertThat(commands).allSatisfy(command ->
                assertThat(command).noneMatch(part -> part.contains("--recurse-submodules")));
    }

    @Test
    @DisplayName("多语言（Java + 整仓 Python）时稀疏检出按扩展名派生，绝不退化成 ** 全量")
    void wholeRepoLanguageYieldsExtensionPatternNotFullCheckout() {
        ScanProperties.SourceSpec java = new ScanProperties.SourceSpec();
        java.setLanguage("java");
        java.setSourceRoot("src/main/java");
        java.setFileExtensions(List.of(".java"));
        ScanProperties.SourceSpec python = new ScanProperties.SourceSpec();
        python.setLanguage("python");
        python.setSourceRoot(".");
        python.setFileExtensions(List.of(".py"));
        ScanProperties multi = new ScanProperties();
        multi.setSources(List.of(java, python));
        scan = multi;

        List<List<String>> commands = cloner("git").buildCommands(tempRoot.resolve("ws"), "https://github.com/a/b");

        assertThat(commands.get(2))
                .contains("**/src/main/java/**", "**/*.py")
                .doesNotContain("**");
    }

    @Test
    @DisplayName("受控环境：关闭 git 与凭据管理器的交互提示，否则访问私有仓库会挂住")
    void disablesInteractivePrompts() {
        GitRepositoryCloner cloner = cloner("git");

        Map<String, String> environment = cloner.gitEnvironment();

        assertThat(environment).containsEntry("GIT_TERMINAL_PROMPT", "0");
        assertThat(environment).containsEntry("GCM_INTERACTIVE", "Never");
    }

    // ---------- 失败清理 ----------

    @Test
    @DisplayName("git 无法执行时返回失败，并清理掉已建立的工作区（不留垃圾目录）")
    void cleansUpWorkspaceWhenGitFails() {
        GitRepositoryCloner cloner = cloner(tempRoot.resolve("no-such-git-binary").toString());

        CloneResult result = cloner.clone("https://github.com/spring-projects/spring-petclinic");

        assertThat(result.success()).isFalse();
        assertThat(result.localPath()).isNull();
        assertThat(result.errorMessage()).isNotBlank();
        assertThat(listTempRoot())
                .as("失败后不能把半成品工作区留在临时根下")
                .isEmpty();
    }

    private List<Path> listTempRoot() {
        try (var stream = Files.list(tempRoot)) {
            return stream.toList();
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    // ---------- 终检：三项限制 ----------

    @Test
    @DisplayName("终检：仓库总大小超限被拒，错误信息指明是哪一项")
    void rejectsRepoExceedingTotalSize() throws IOException {
        Path repo = Files.createDirectories(tempRoot.resolve("repo"));
        Files.write(repo.resolve("big.bin"), new byte[4096]);
        GitRepositoryCloner cloner = cloner("git", p -> p.getLimits().setMaxRepoBytes(1024));

        java.util.Optional<String> error = cloner.checkLimits(repo);

        assertThat(error).isPresent();
        assertThat(error.get()).contains("仓库总大小").contains("超过上限").doesNotContain("单文件");
    }

    @Test
    @DisplayName("终检：文件数超限被拒")
    void rejectsRepoExceedingFileCount() throws IOException {
        Path repo = Files.createDirectories(tempRoot.resolve("repo"));
        Files.writeString(repo.resolve("a.java"), "a");
        Files.writeString(repo.resolve("b.java"), "b");
        Files.writeString(repo.resolve("c.java"), "c");
        GitRepositoryCloner cloner = cloner("git", p -> p.getLimits().setMaxFiles(2));

        java.util.Optional<String> error = cloner.checkLimits(repo);

        assertThat(error).isPresent();
        assertThat(error.get()).contains("文件数");
    }

    @Test
    @DisplayName("终检：单个文件超限被拒，并指出是哪个文件")
    void rejectsOversizedSingleFile() throws IOException {
        Path repo = Files.createDirectories(tempRoot.resolve("repo"));
        Files.write(repo.resolve("Generated.java"), new byte[8192]);
        GitRepositoryCloner cloner = cloner("git", p -> p.getLimits().setMaxFileBytes(1024));

        java.util.Optional<String> error = cloner.checkLimits(repo);

        assertThat(error).isPresent();
        assertThat(error.get()).contains("单文件").contains("Generated.java");
    }

    @Test
    @DisplayName("终检：三项都在限内则通过")
    void acceptsRepoWithinLimits() throws IOException {
        Path repo = Files.createDirectories(tempRoot.resolve("repo"));
        Files.writeString(repo.resolve("OwnerController.java"), "class OwnerController {}");
        GitRepositoryCloner cloner = cloner("git");

        assertThat(cloner.checkLimits(repo)).isEmpty();
    }

    @Test
    @DisplayName("超时文案必须点明可能原因，否则会把'仓库不存在'误报成网络问题")
    void timeoutMessageNamesLikelyCauses() {
        GitRepositoryCloner cloner = cloner("git");

        String message = cloner.timeoutMessage("fatal: whatever");

        assertThat(message).contains("超时").contains("仓库不存在").contains("私有");
        assertThat(message).contains("fatal: whatever");
    }
}
