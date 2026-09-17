package com.codecompass.service;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ExecutorService;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.codecompass.analyzer.AnalyzeRequest;
import com.codecompass.analyzer.AnalyzeResult;
import com.codecompass.analyzer.LanguageAnalyzer;
import com.codecompass.analyzer.LanguageAnalyzerRegistry;
import com.codecompass.analyzer.UnitRoleAnnotator;
import com.codecompass.graph.DependencyGraph;
import com.codecompass.graph.DependencyGraphBuilder;
import com.codecompass.repo.CloneResult;
import com.codecompass.repo.CodeUnitFileInfo;
import com.codecompass.repo.GitRepositoryCloner;
import com.codecompass.repo.SourceFileScanner;
import com.codecompass.repo.TempWorkspaceManager;

/**
 * 分析编排器：把 T1 克隆 → T2 扫描 → T3 派发 → T4 分析 → T5 角色 → T6 构图串成后台任务。
 *
 * <p><b>管线绝不跑在请求线程上</b> —— 克隆最长 60 秒，同步执行会耗尽 Tomcat 线程池。
 * POST 只负责建任务与提交后台池。
 *
 * <p><b>语言隔离</b>：本类只依赖语言中立的接口（注册表、{@link UnitRoleAnnotator}），
 * 不 import {@code analyzer/java/} 的任何类型。角色映射来自中立 seam。
 *
 * <p><b>失败路径单一出口</b>：任何阶段失败都清理工作区 + 原子发布 failed，不留半成品。
 */
public class AnalysisOrchestrator {

    private static final Logger log = LoggerFactory.getLogger(AnalysisOrchestrator.class);

    private static final int REASON_LIMIT = 500;

    private final AnalysisTaskStore store;
    private final ExecutorService executor;
    private final GitRepositoryCloner cloner;
    private final SourceFileScanner scanner;
    private final LanguageAnalyzerRegistry registry;
    private final UnitRoleAnnotator roleAnnotator;
    private final DependencyGraphBuilder graphBuilder;
    private final TempWorkspaceManager tempWorkspaceManager;

    public AnalysisOrchestrator(AnalysisTaskStore store,
                                ExecutorService executor,
                                GitRepositoryCloner cloner,
                                SourceFileScanner scanner,
                                LanguageAnalyzerRegistry registry,
                                UnitRoleAnnotator roleAnnotator,
                                DependencyGraphBuilder graphBuilder,
                                TempWorkspaceManager tempWorkspaceManager) {
        this.store = store;
        this.executor = executor;
        this.cloner = cloner;
        this.scanner = scanner;
        this.registry = registry;
        this.roleAnnotator = roleAnnotator;
        this.graphBuilder = graphBuilder;
        this.tempWorkspaceManager = tempWorkspaceManager;
    }

    /** 建任务并提交后台执行，立即返回 taskId —— 调用方（控制器）不等待结果。 */
    public String submit(String url) {
        String taskId = store.create(url);
        executor.execute(() -> runPipeline(taskId));
        return taskId;
    }

    private void runPipeline(String taskId) {
        String url = store.find(taskId).orElseThrow().url();
        Path repoDir = null;
        try {
            store.update(taskId, snapshot -> snapshot.withProgress(
                    AnalysisTaskSnapshot.STATUS_RUNNING, 10, "克隆中"));
            CloneResult cloneResult = cloner.clone(url);
            if (!cloneResult.success()) {
                store.update(taskId, snapshot -> snapshot.withFailure(
                        orDefault(cloneResult.errorMessage())));
                return;
            }
            repoDir = Path.of(cloneResult.localPath());

            store.update(taskId, snapshot -> snapshot.withProgress(
                    AnalysisTaskSnapshot.STATUS_RUNNING, 40, "扫描源码"));
            List<CodeUnitFileInfo> files = scanner.scan(repoDir);
            if (files.isEmpty()) {
                // 仓库里没有可解析的源码：done + 空图 + 明确提示，而不是报错
                AnalyzeResult emptyResult = AnalyzeResult.empty(url, "", "");
                DependencyGraph emptyGraph = graphBuilder.build(emptyResult, Map.of());
                store.update(taskId, snapshot -> snapshot.withDone(
                        new AnalysisTaskSnapshot.AnalysisOutcome(
                                emptyResult, emptyGraph, Map.of(), Map.of()),
                        "", "未发现可解析的源码"));
                return;
            }

            String language = files.stream()
                    .map(CodeUnitFileInfo::language)
                    .filter(Objects::nonNull)
                    .filter(value -> !value.isBlank())
                    .findFirst()
                    .orElse("");

            store.update(taskId, snapshot -> snapshot.withProgress(
                    AnalysisTaskSnapshot.STATUS_RUNNING, 60, "解析源码"));
            LanguageAnalyzer analyzer = registry.forLanguage(language)
                    .orElseThrow(() -> new IllegalStateException("不支持的语言：" + language
                            + "，已支持 " + registry.supportedLanguages()));

            AnalyzeResult result = analyzer.analyze(new AnalyzeRequest(url, repoDir, files));
            Map<String, String> roles = roleAnnotator.annotate(result);

            store.update(taskId, snapshot -> snapshot.withProgress(
                    AnalysisTaskSnapshot.STATUS_RUNNING, 90, "构建依赖图"));
            DependencyGraph graph = graphBuilder.build(result, roles);

            // T7 决策 ②：§07 底线要求分析完删工作区，T9/T10 又需要源码内容，
            // 所以删除前把文件内容快照进内存结果。
            Map<String, List<String>> sourceLines = snapshotSources(repoDir, files);

            store.update(taskId, snapshot -> snapshot.withDone(
                    new AnalysisTaskSnapshot.AnalysisOutcome(result, graph, roles, sourceLines),
                    language, "分析完成"));
        } catch (Exception e) {
            log.error("分析任务失败：{}（{}）", taskId, e.getMessage(), e);
            store.update(taskId, snapshot -> snapshot.withFailure(summarize(e)));
        } finally {
            if (repoDir != null) {
                try {
                    tempWorkspaceManager.delete(repoDir.getParent());
                } catch (RuntimeException cleanupFailure) {
                    // 清理失败不能掩盖任务本身的结局
                    log.warn("清理工作区失败：{}", cleanupFailure.getMessage());
                }
            }
        }
    }

    private static Map<String, List<String>> snapshotSources(Path repoDir, List<CodeUnitFileInfo> files) {
        Map<String, List<String>> lines = new LinkedHashMap<>();
        for (CodeUnitFileInfo file : files) {
            try {
                lines.put(file.relativePath(),
                        Files.readAllLines(repoDir.resolve(file.relativePath()), StandardCharsets.UTF_8));
            } catch (IOException e) {
                log.warn("快照源码失败，跳过：{}（{}）", file.relativePath(), e.getMessage());
            }
        }
        return lines;
    }

    private static String summarize(Exception e) {
        String message = e.getMessage();
        if (message == null || message.isBlank()) {
            message = e.getClass().getSimpleName();
        }
        String trimmed = message.replaceAll("\\s+", " ").trim();
        return trimmed.length() <= REASON_LIMIT ? trimmed : trimmed.substring(0, REASON_LIMIT) + "…";
    }

    private static String orDefault(String value) {
        return value == null || value.isBlank() ? "克隆失败（无详细信息）" : value;
    }
}
