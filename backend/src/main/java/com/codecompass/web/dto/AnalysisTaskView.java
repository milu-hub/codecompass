package com.codecompass.web.dto;

import java.time.Instant;

import com.codecompass.service.AnalysisTaskSnapshot;

/**
 * 任务状态的 web 视图。
 *
 * <p>刻意不直接序列化 {@link AnalysisTaskSnapshot}：它的 outcome 里带着完整结果与源码快照，
 * 属于内部状态。状态端点只暴露进度字段。
 *
 * <p>{@code language} 在扫描完成前为 null，但字段始终存在 —— 结构稳定（T7 要求 1）。
 */
public record AnalysisTaskView(
        String taskId,
        String url,
        String status,
        String language,
        int progress,
        String message,
        String errorMessage,
        Instant createdAt,
        Instant updatedAt) {

    public static AnalysisTaskView from(AnalysisTaskSnapshot snapshot) {
        return new AnalysisTaskView(snapshot.taskId(), snapshot.url(), snapshot.status(),
                snapshot.language(), snapshot.progress(), snapshot.message(),
                snapshot.errorMessage(), snapshot.createdAt(), snapshot.updatedAt());
    }
}
