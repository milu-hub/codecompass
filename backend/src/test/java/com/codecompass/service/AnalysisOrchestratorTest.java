package com.codecompass.service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.Mockito;

import com.codecompass.analyzer.LanguageAnalyzerRegistry;
import com.codecompass.analyzer.java.CoreAnnotationClassifier;
import com.codecompass.analyzer.java.JavaAnalyzeProperties;
import com.codecompass.analyzer.java.JavaSpringAnalyzer;
import com.codecompass.graph.DependencyGraphBuilder;
import com.codecompass.graph.MermaidRenderer;
import com.codecompass.repo.CloneResult;
import com.codecompass.repo.CodeUnitFileInfo;
import com.codecompass.repo.GitRepositoryCloner;
import com.codecompass.repo.ScanProperties;
import com.codecompass.repo.SourceFileScanner;
import com.codecompass.repo.TempWorkspaceManager;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;

/**
 * 分析编排器：把 T1 克隆 → T2 扫描 → T3 派发 → T4 分析 → T5 角色 → T6 构图串成后台任务。
 *
 * 除克隆外全部用真实组件，克隆用 Mockito 假件 —— 这样流水线在合成仓库上真跑，
 * 又不碰网络。失败隔离、工作区清理、内容快照都在真实文件系统上验证。
 */
class AnalysisOrchestratorTest {

    @TempDir
    Path tempDir;

    private Path tempRoot;
    private AnalysisTaskStore store;
    private GitRepositoryCloner cloner;
    private SourceFileScanner scanner;
    private TempWorkspaceManager tempWorkspaceManager;
    private AnalysisOrchestrator orchestrator;
    private ExecutorService executor;

    @BeforeEach
    void setUp() throws IOException {
        tempRoot = Files.createDirectories(tempDir.resolve("cc-root"));
        store = new AnalysisTaskStore();
        cloner = Mockito.mock(GitRepositoryCloner.class);
        tempWorkspaceManager = new TempWorkspaceManager(tempRoot);

        ScanProperties.SourceSpec java = new ScanProperties.SourceSpec();
        java.setLanguage("java");
        java.setSourceRoot("src/main/java");
        java.setFileExtensions(List.of(".java"));
        java.setExcludedFileNames(List.of("package-info.java", "module-info.java"));
        ScanProperties scanProperties = new ScanProperties();
        scanProperties.setSources(List.of(java));
        scanner = new SourceFileScanner(scanProperties);

        JavaAnalyzeProperties analyzeProperties = new JavaAnalyzeProperties();
        analyzeProperties.setFrameworkMarkers(Map.of("spring", List.of("@Controller", "@Service")));
        analyzeProperties.setCoreAnnotations(Map.of("spring", Map.of(
                "controller", List.of("@Controller"),
                "service", List.of("@Service"),
                "member", List.of("@Autowired", "@Bean"))));
        JavaSpringAnalyzer analyzer = new JavaSpringAnalyzer(analyzeProperties);
        LanguageAnalyzerRegistry registry = new LanguageAnalyzerRegistry(List.of(analyzer));
        CoreAnnotationClassifier classifier = new CoreAnnotationClassifier(analyzeProperties);
        DependencyGraphBuilder graphBuilder = new DependencyGraphBuilder(new MermaidRenderer());

        executor = Executors.newSingleThreadExecutor(runnable -> {
            Thread thread = new Thread(runnable, "analysis-orchestrator-test");
            thread.setDaemon(true);
            return thread;
        });
        orchestrator = new AnalysisOrchestrator(store, executor, cloner, scanner, registry,
                classifier, graphBuilder, tempWorkspaceManager);
    }

    @AfterEach
    void tearDown() {
        executor.shutdownNow();
    }

    @Test
    @DisplayName("成功流水线：发布 done + 图 + 角色 + 内容快照，且工作区已被删除")
    void successfulPipelinePublishesDoneAndDeletesWorkspace() throws IOException {
        Path jobDir = tempRoot.resolve("job-1");
        Path repoDir = writeSource(jobDir, "src/main/java/com/example/OwnerController.java", """
                package com.example;

                @Controller
                public class OwnerController {
                    private OwnerRepository repo;
                }
                """);
        writeSource(jobDir, "src/main/java/com/example/OwnerRepository.java", """
                package com.example;
                public class OwnerRepository {
                }
                """);
        Mockito.when(cloner.clone(anyString())).thenReturn(CloneResult.ok(repoDir.toString()));

        String taskId = orchestrator.submit("https://github.com/a/b");
        AnalysisTaskSnapshot done = awaitTerminal(taskId);

        assertThat(done.status()).isEqualTo(AnalysisTaskSnapshot.STATUS_DONE);
        assertThat(done.language()).isEqualTo("java");
        assertThat(done.errorMessage()).isNull();

        // 图与角色
        assertThat(done.outcome()).isNotNull();
        assertThat(done.outcome().graph().mermaid()).startsWith("graph LR").contains("-->");
        assertThat(done.outcome().roles()).containsEntry(
                controllerId(done), "controller");

        // 内容快照：T7 决策 ② —— 工作区删了，T9/T10 的代码内容在这里
        assertThat(done.outcome().sourceLines())
                .containsKey("src/main/java/com/example/OwnerController.java");
        assertThat(done.outcome().sourceLines()
                .get("src/main/java/com/example/OwnerController.java"))
                .anyMatch(line -> line.contains("class OwnerController"));

        // §07 底线：分析完删除临时目录
        assertThat(jobDir).as("工作区必须已删除").doesNotExist();
    }

    @Test
    @DisplayName("克隆失败：发布 failed + 错误信息，且不残留工作区")
    void cloneFailurePublishesFailed() {
        Mockito.when(cloner.clone(anyString()))
                .thenReturn(CloneResult.fail("克隆超时（60s）。可能原因：仓库不存在、是私有仓库，或网络过慢。"));

        String taskId = orchestrator.submit("https://github.com/a/b");
        AnalysisTaskSnapshot failed = awaitTerminal(taskId);

        assertThat(failed.status()).isEqualTo(AnalysisTaskSnapshot.STATUS_FAILED);
        assertThat(failed.errorMessage()).contains("克隆超时");
        assertThat(failed.outcome()).isNull();
    }

    @Test
    @DisplayName("仓库没有任何可解析源码：done + 空图 + 明确提示，而不是 failed")
    void emptyRepositoryPublishesDoneWithMessage() throws IOException {
        Path jobDir = tempRoot.resolve("job-2");
        Path repoDir = Files.createDirectories(jobDir.resolve("repo"));
        Mockito.when(cloner.clone(anyString())).thenReturn(CloneResult.ok(repoDir.toString()));

        String taskId = orchestrator.submit("https://github.com/a/b");
        AnalysisTaskSnapshot done = awaitTerminal(taskId);

        assertThat(done.status()).isEqualTo(AnalysisTaskSnapshot.STATUS_DONE);
        assertThat(done.message()).contains("未发现可解析的源码");
        assertThat(done.outcome()).isNotNull();
        assertThat(done.outcome().graph().nodes()).isEmpty();
        assertThat(done.outcome().graph().mermaid()).isNotBlank();
        assertThat(jobDir).doesNotExist();
    }

    @Test
    @DisplayName("不支持的语言：failed 且消息列出已支持的语言，而不是返回空结果")
    void unsupportedLanguagePublishesFailed() throws IOException {
        Path jobDir = tempRoot.resolve("job-3");
        Path repoDir = Files.createDirectories(jobDir.resolve("repo"));
        Mockito.when(cloner.clone(anyString())).thenReturn(CloneResult.ok(repoDir.toString()));
        SourceFileScanner klingonScanner = Mockito.mock(SourceFileScanner.class);
        Mockito.when(klingonScanner.scan(Mockito.any()))
                .thenReturn(List.of(new CodeUnitFileInfo("a.klingon", "", "", "klingon")));
        AnalysisOrchestrator klingonOrchestrator = new AnalysisOrchestrator(
                store, executor, cloner, klingonScanner,
                new LanguageAnalyzerRegistry(List.of()), new CoreAnnotationClassifier(new JavaAnalyzeProperties()),
                new DependencyGraphBuilder(new MermaidRenderer()), tempWorkspaceManager);

        String taskId = klingonOrchestrator.submit("https://github.com/a/b");
        AnalysisTaskSnapshot failed = awaitTerminal(taskId);

        assertThat(failed.status()).isEqualTo(AnalysisTaskSnapshot.STATUS_FAILED);
        assertThat(failed.errorMessage()).contains("不支持的语言").contains("klingon");
        assertThat(jobDir).doesNotExist();
    }

    @Test
    @DisplayName("同 URL 两次提交产生两个独立任务（去重是 T11 缓存的事）")
    void repeatedSubmissionsAreIndependentTasks() throws IOException {
        Path jobDir = tempRoot.resolve("job-4");
        Path repoDir = Files.createDirectories(jobDir.resolve("repo"));
        Mockito.when(cloner.clone(anyString())).thenReturn(CloneResult.ok(repoDir.toString()));

        String first = orchestrator.submit("https://github.com/a/b");
        awaitTerminal(first);
        String second = orchestrator.submit("https://github.com/a/b");
        awaitTerminal(second);

        assertThat(first).isNotEqualTo(second);
        assertThat(store.find(first).orElseThrow().status())
                .isEqualTo(AnalysisTaskSnapshot.STATUS_DONE);
        assertThat(store.find(second).orElseThrow().status())
                .isEqualTo(AnalysisTaskSnapshot.STATUS_DONE);
    }

    // ---------- helpers ----------

    private Path writeSource(Path jobDir, String relativePath, String source) throws IOException {
        Path repoDir = jobDir.resolve("repo");
        Path target = repoDir.resolve(relativePath);
        Files.createDirectories(target.getParent());
        Files.writeString(target, source);
        return repoDir;
    }

    private AnalysisTaskSnapshot awaitTerminal(String taskId) {
        long deadline = System.nanoTime() + Duration.ofSeconds(30).toNanos();
        while (System.nanoTime() < deadline) {
            AnalysisTaskSnapshot snapshot = store.find(taskId).orElseThrow();
            if (snapshot.status().equals(AnalysisTaskSnapshot.STATUS_DONE)
                    || snapshot.status().equals(AnalysisTaskSnapshot.STATUS_FAILED)) {
                return snapshot;
            }
            try {
                Thread.sleep(50);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new AssertionError("等待任务结束时被中断");
            }
        }
        throw new AssertionError("任务未在期限内结束：" + store.find(taskId));
    }

    private static String controllerId(AnalysisTaskSnapshot snapshot) {
        return snapshot.outcome().result().codeUnits().stream()
                .filter(unit -> unit.name().equals("OwnerController"))
                .findFirst().orElseThrow().id();
    }
}
