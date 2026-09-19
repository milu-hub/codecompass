package com.codecompass.repo;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 源码文件扫描器。
 *
 * 扫描器必须是**语言无关**的：它只知道"配置里声明了某个源码根、某些扩展名、某个 language 标签"，
 * 不对任何语言做分支判断。Java 的语法知识属于 T3/T4 的 LanguageAnalyzer。
 */
class SourceFileScannerTest {

    @TempDir
    Path repoRoot;

    private SourceFileScanner scanner() {
        ScanProperties properties = new ScanProperties();
        properties.setSources(List.of(javaSource()));
        return new SourceFileScanner(properties);
    }

    /** 与 application.yml 里的配置等价，测试里显式构造以便逐项调参。 */
    private ScanProperties.SourceSpec javaSource() {
        ScanProperties.SourceSpec spec = new ScanProperties.SourceSpec();
        spec.setLanguage("java");
        spec.setSourceRoot("src/main/java");
        spec.setFileExtensions(List.of(".java"));
        spec.setExcludedFileNames(List.of("package-info.java", "module-info.java"));
        return spec;
    }

    // ---------- 最关键的一条：Java glob 方言回归 ----------

    @Test
    @DisplayName("回归：根模块的 src/main/java 必须命中——Java glob 的 **/ 匹配不了零层前缀")
    void matchesRootModuleSources() throws IOException {
        write("src/main/java/org/foo/Bar.java");

        List<CodeUnitFileInfo> found = scanner().scan(repoRoot);

        assertThat(found)
                .as("真机克隆的 petclinic 就是这种布局；若这里返回空，黄金样本直接归零且不报错")
                .hasSize(1);
        assertThat(found.get(0).relativePath()).isEqualTo("src/main/java/org/foo/Bar.java");
    }

    // ---------- 包名与类名推导 ----------

    @Test
    @DisplayName("包名由源码根之后的目录层级推导，类名取文件名")
    void derivesPackageAndUnitName() throws IOException {
        write("src/main/java/org/springframework/samples/petclinic/owner/OwnerController.java");

        CodeUnitFileInfo info = scanner().scan(repoRoot).get(0);

        assertThat(info.packageName()).isEqualTo("org.springframework.samples.petclinic.owner");
        assertThat(info.unitName()).isEqualTo("OwnerController");
        assertThat(info.language()).isEqualTo("java");
    }

    @Test
    @DisplayName("默认包：文件直接位于源码根下时 packageName 为空串而不是 null")
    void defaultPackageYieldsEmptyName() throws IOException {
        write("src/main/java/Application.java");

        CodeUnitFileInfo info = scanner().scan(repoRoot).get(0);

        assertThat(info.packageName()).isEmpty();
        assertThat(info.unitName()).isEqualTo("Application");
    }

    // ---------- 多模块 ----------

    @Test
    @DisplayName("多模块：每个模块的 src/main/java 都扫到，包名互不干扰")
    void scansEveryModule() throws IOException {
        write("module-a/src/main/java/com/a/Alpha.java");
        write("module-b/src/main/java/com/b/Beta.java");
        write("module-b/sub/src/main/java/com/b/sub/Gamma.java");

        List<CodeUnitFileInfo> found = scanner().scan(repoRoot);

        assertThat(found).extracting(CodeUnitFileInfo::relativePath).containsExactly(
                "module-a/src/main/java/com/a/Alpha.java",
                "module-b/src/main/java/com/b/Beta.java",
                "module-b/sub/src/main/java/com/b/sub/Gamma.java");
        assertThat(found).extracting(CodeUnitFileInfo::packageName)
                .containsExactly("com.a", "com.b", "com.b.sub");
    }

    @Test
    @DisplayName("多模块同名类不去重：relativePath 不同就是两条记录")
    void keepsSameNamedClassesFromDifferentModules() throws IOException {
        write("module-a/src/main/java/com/foo/Application.java");
        write("module-b/src/main/java/com/foo/Application.java");

        List<CodeUnitFileInfo> found = scanner().scan(repoRoot);

        assertThat(found)
                .as("按 unitName 去重会静默丢掉一个模块的类")
                .hasSize(2);
    }

    @Test
    @DisplayName("源码根在路径中重复出现时取最后一次，不取第一次")
    void usesLastSourceRootOccurrence() throws IOException {
        write("outer/src/main/java/inner/src/main/java/com/deep/Target.java");

        CodeUnitFileInfo info = scanner().scan(repoRoot).get(0);

        assertThat(info.packageName()).isEqualTo("com.deep");
    }

    // ---------- 排除与过滤 ----------

    @Test
    @DisplayName("忽略 package-info.java 与 module-info.java")
    void ignoresPackageAndModuleInfo() throws IOException {
        write("src/main/java/org/foo/package-info.java");
        write("src/main/java/module-info.java");
        write("src/main/java/org/foo/Real.java");

        List<CodeUnitFileInfo> found = scanner().scan(repoRoot);

        assertThat(found).extracting(CodeUnitFileInfo::unitName).containsExactly("Real");
    }

    @Test
    @DisplayName("只取配置声明的扩展名，同目录其它文件忽略")
    void respectsConfiguredExtensions() throws IOException {
        write("src/main/java/org/foo/Bar.java");
        write("src/main/java/org/foo/notes.txt");
        write("src/main/java/org/foo/data.json");

        List<CodeUnitFileInfo> found = scanner().scan(repoRoot);

        assertThat(found).hasSize(1);
    }

    @Test
    @DisplayName("src/test/java 不在源码根下，不扫")
    void ignoresTestSources() throws IOException {
        write("src/test/java/org/foo/BarTest.java");
        write("src/main/java/org/foo/Bar.java");

        List<CodeUnitFileInfo> found = scanner().scan(repoRoot);

        assertThat(found).extracting(CodeUnitFileInfo::unitName).containsExactly("Bar");
    }

    @Test
    @DisplayName("完全不匹配源码根的文件忽略（如根目录的 build.gradle）")
    void ignoresFilesOutsideAnySourceRoot() throws IOException {
        write("build.gradle");
        write("README.md");
        write("other/java/NotUnderRoot.java");

        assertThat(scanner().scan(repoRoot)).isEmpty();
    }

    // ---------- 契约细节 ----------

    @Test
    @DisplayName("relativePath 相对仓库根且用 / 分隔（Windows 上不是反斜杠）")
    void relativePathUsesForwardSlashes() throws IOException {
        write("module-a/src/main/java/com/a/Alpha.java");

        CodeUnitFileInfo info = scanner().scan(repoRoot).get(0);

        assertThat(info.relativePath()).doesNotContain("\\").startsWith("module-a/");
        assertThat(info.relativePath()).doesNotStartWith("/");
    }

    @Test
    @DisplayName("language 原样取自配置，扫描器不解释其含义")
    void carriesLanguageFromConfiguration() throws IOException {
        write("src/main/java/org/foo/Bar.java");

        ScanProperties properties = new ScanProperties();
        ScanProperties.SourceSpec spec = javaSource();
        spec.setLanguage("klingon");
        properties.setSources(List.of(spec));

        assertThat(new SourceFileScanner(properties).scan(repoRoot).get(0).language())
                .isEqualTo("klingon");
    }

    @Test
    @DisplayName("结果按 relativePath 排序，保证确定性")
    void resultIsSortedDeterministically() throws IOException {
        write("module-b/src/main/java/com/b/Beta.java");
        write("module-a/src/main/java/com/a/Alpha.java");
        write("module-c/src/main/java/com/c/Gamma.java");

        List<CodeUnitFileInfo> found = scanner().scan(repoRoot);

        assertThat(found).extracting(CodeUnitFileInfo::relativePath).isSorted();
    }

    @Test
    @DisplayName("仓库根不存在时返回空列表，不抛异常")
    void missingRootYieldsEmptyList() {
        assertThat(scanner().scan(repoRoot.resolve("not-there"))).isEmpty();
    }

    @Test
    @DisplayName("跳过 .git 目录，不进版本库内部翻文件")
    void skipsGitDirectory() throws IOException {
        write(".git/objects/pack/fake.java");
        write("src/main/java/org/foo/Bar.java");

        List<CodeUnitFileInfo> found = scanner().scan(repoRoot);

        assertThat(found).hasSize(1);
    }

    // ---------- P2：Python 整仓扫描 ----------

    private SourceFileScanner pythonScanner() {
        ScanProperties properties = new ScanProperties();
        ScanProperties.SourceSpec python = new ScanProperties.SourceSpec();
        python.setLanguage("python");
        python.setSourceRoot(".");
        python.setFileExtensions(List.of(".py"));
        python.setExcludedFileNames(List.of());
        python.setExcludedDirectoryNames(List.of(
                "tests", "test", "venv", ".venv", "site-packages", "node_modules",
                "__pycache__", ".tox", ".mypy_cache", ".pytest_cache", "build", "dist"));
        properties.setSources(List.of(python));
        return new SourceFileScanner(properties);
    }

    @Test
    @DisplayName("P2 整仓模式：顶层 setup.py 与深层模块都收下，包名从仓库根起算")
    void pythonWholeRepoMatchesAnyDepth() throws IOException {
        write("setup.py");
        write("app/models/user.py");
        write("app/views/__init__.py");

        List<CodeUnitFileInfo> found = pythonScanner().scan(repoRoot);

        assertThat(found).extracting(CodeUnitFileInfo::relativePath)
                .containsExactlyInAnyOrder("app/models/user.py", "app/views/__init__.py", "setup.py");
        assertThat(found)
                .filteredOn(info -> info.relativePath().equals("app/models/user.py"))
                .singleElement()
                .satisfies(info -> {
                    assertThat(info.packageName()).isEqualTo("app.models");
                    assertThat(info.unitName()).isEqualTo("user");
                    assertThat(info.language()).isEqualTo("python");
                });
        assertThat(found)
                .filteredOn(info -> info.relativePath().equals("setup.py"))
                .singleElement()
                .satisfies(info -> assertThat(info.packageName()).isEqualTo(""));
    }

    @Test
    @DisplayName("P2 目录排除：tests/venv/.venv/node_modules/__pycache__ 全部跳过")
    void pythonSkipsExcludedDirectories() throws IOException {
        write("tests/test_app.py");
        write("venv/lib/site-packages/thirdparty.py");
        write(".venv/lib/dep.py");
        write("node_modules/pkg/index.py");
        write("app/__pycache__/cached.py");
        write("app/main.py");

        List<CodeUnitFileInfo> found = pythonScanner().scan(repoRoot);

        assertThat(found).extracting(CodeUnitFileInfo::relativePath)
                .containsExactly("app/main.py");
    }

    @Test
    @DisplayName("P2 混仓：Java 与 Python 各按自己的配置命中，互不干扰")
    void mixedRepositoryScansEachLanguageByItsOwnConfig() throws IOException {
        ScanProperties properties = new ScanProperties();
        properties.setSources(List.of(javaSource(), pythonSourceSpec()));
        SourceFileScanner mixed = new SourceFileScanner(properties);

        write("src/main/java/org/foo/Bar.java");
        write("src/main/java/org/foo/package-info.java"); // Java 的文件级排除
        write("app/main.py");
        write("setup.py");
        write("tests/test_x.py"); // Python 的目录级排除

        List<CodeUnitFileInfo> found = mixed.scan(repoRoot);

        assertThat(found).extracting(CodeUnitFileInfo::language)
                .containsExactlyInAnyOrder("java", "python", "python");
        assertThat(found).extracting(CodeUnitFileInfo::relativePath)
                .contains("src/main/java/org/foo/Bar.java", "app/main.py", "setup.py")
                .doesNotContain("src/main/java/org/foo/package-info.java", "tests/test_x.py");
    }

    @Test
    @DisplayName("P2 目录排除只对整仓语言生效：Java 的 test 包目录不被 Python 的 test 排除误伤")
    void directoryExclusionDoesNotLeakAcrossLanguages() throws IOException {
        ScanProperties properties = new ScanProperties();
        properties.setSources(List.of(javaSource(), pythonSourceSpec()));
        SourceFileScanner mixed = new SourceFileScanner(properties);

        // Java 的包目录名恰好是 "test"（如 cn.javastack.springboot.test），
        // Python 的 excludedDirectoryNames 含 "test"，但不能把 Java 的这个包也排除掉。
        write("src/main/java/cn/example/test/Foo.java");
        write("tests/test_x.py");
        write("app/main.py");

        List<CodeUnitFileInfo> found = mixed.scan(repoRoot);

        assertThat(found).extracting(CodeUnitFileInfo::relativePath)
                .contains("src/main/java/cn/example/test/Foo.java", "app/main.py")
                .doesNotContain("tests/test_x.py");
    }

    @Test
    @DisplayName("稀疏检出模式：整仓 source-root 按扩展名派生（**/*.py），不是 ** 全量")
    void wholeRepoSourceRootYieldsExtensionPattern() {
        ScanProperties.SourceSpec python = pythonSourceSpec();

        assertThat(python.sparseCheckoutPatterns()).containsExactly("**/*.py");
        assertThat(javaSource().sparseCheckoutPatterns()).containsExactly("**/src/main/java/**");
    }

    private ScanProperties.SourceSpec pythonSourceSpec() {
        ScanProperties.SourceSpec spec = new ScanProperties.SourceSpec();
        spec.setLanguage("python");
        spec.setSourceRoot(".");
        spec.setFileExtensions(List.of(".py"));
        spec.setExcludedFileNames(List.of());
        spec.setExcludedDirectoryNames(List.of(
                "tests", "test", "venv", ".venv", "site-packages", "node_modules",
                "__pycache__", ".tox", ".mypy_cache", ".pytest_cache", "build", "dist"));
        return spec;
    }

    private void write(String relativePath) throws IOException {
        Path target = repoRoot.resolve(relativePath);
        Files.createDirectories(target.getParent());
        Files.writeString(target, "// placeholder\n");
    }
}
