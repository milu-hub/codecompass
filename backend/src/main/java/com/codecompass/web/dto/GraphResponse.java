package com.codecompass.web.dto;

import java.util.List;

import com.codecompass.analyzer.DependencyEdge;
import com.codecompass.analyzer.FailedFile;
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
        AnalysisTaskSnapshot.AnalysisOutcome outcome = snapshot.outcome();
        List<UnitView> units = outcome.result().codeUnits().stream()
                .map(unit -> new UnitView(unit.id(), unit.filePath(), unit.packageName(),
                        unit.name(), unit.kind(), outcome.roles().getOrDefault(unit.id(), ""),
                        unit.annotations(), unit.startLine(), unit.endLine()))
                .toList();
        return new GraphResponse(snapshot.taskId(), snapshot.url(), snapshot.status(),
                snapshot.language(), outcome.result().framework(), null,
                units, outcome.result().dependencies(), outcome.result().failedFiles(),
                outcome.graph().isolatedCodeUnitIds(), outcome.graph().mermaid());
    }

    public static GraphResponse failed(AnalysisTaskSnapshot snapshot) {
        return new GraphResponse(snapshot.taskId(), snapshot.url(), snapshot.status(),
                snapshot.language(), "", snapshot.errorMessage(),
                List.of(), List.of(), List.of(), List.of(), "");
    }
}
