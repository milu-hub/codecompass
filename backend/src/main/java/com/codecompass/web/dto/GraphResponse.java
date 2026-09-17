package com.codecompass.web.dto;

import java.util.List;

import com.codecompass.analyzer.DependencyEdge;
import com.codecompass.analyzer.FailedFile;
import com.codecompass.graph.DependencyGraph;
import com.codecompass.service.AnalysisTaskSnapshot;

/**
 * GET /api/repos/{id}/graph 的响应。
 *
 * <p>done：完整结果（codeUnits 含 role、dependencies、failedFiles、isolatedCodeUnitIds、mermaid）。
 * failed：status 与 errorMessage，列表为空。两种形态字段一致 —— 结构稳定。
 */
public record GraphResponse(
        String taskId,
        String repositoryUrl,
        String status,
        String language,
        String framework,
        String errorMessage,
        List<UnitView> codeUnits,
        List<DependencyEdge> dependencies,
        List<FailedFile> failedFiles,
        List<String> isolatedCodeUnitIds,
        String mermaid) {

    public static GraphResponse from(AnalysisTaskSnapshot snapshot) {
        return new GraphResponse(snapshot.taskId(), snapshot.url(), snapshot.status(),
                snapshot.language(), snapshot.outcome().result().framework(), null,
                unitsOf(snapshot), snapshot.outcome().result().dependencies(),
                snapshot.outcome().result().failedFiles(),
                snapshot.outcome().graph().isolatedCodeUnitIds(), snapshot.outcome().graph().mermaid());
    }

    /**
     * 邻域视图（T8 点击某个类）：{@code codeUnits} 保持全量 —— 类列表是稳定锚点；
     * 边、孤立列表与 mermaid 取邻域图。
     */
    public static GraphResponse scoped(AnalysisTaskSnapshot snapshot, DependencyGraph scopedGraph) {
        return new GraphResponse(snapshot.taskId(), snapshot.url(), snapshot.status(),
                snapshot.language(), snapshot.outcome().result().framework(), null,
                unitsOf(snapshot), scopedGraph.edges(), snapshot.outcome().result().failedFiles(),
                scopedGraph.isolatedCodeUnitIds(), scopedGraph.mermaid());
    }

    public static GraphResponse failed(AnalysisTaskSnapshot snapshot) {
        return new GraphResponse(snapshot.taskId(), snapshot.url(), snapshot.status(),
                snapshot.language(), "", snapshot.errorMessage(),
                List.of(), List.of(), List.of(), List.of(), "");
    }

    private static List<UnitView> unitsOf(AnalysisTaskSnapshot snapshot) {
        AnalysisTaskSnapshot.AnalysisOutcome outcome = snapshot.outcome();
        return outcome.result().codeUnits().stream()
                .map(unit -> new UnitView(unit.id(), unit.filePath(), unit.packageName(),
                        unit.name(), unit.kind(), outcome.roles().getOrDefault(unit.id(), ""),
                        unit.annotations(), unit.startLine(), unit.endLine()))
                .toList();
    }
}
