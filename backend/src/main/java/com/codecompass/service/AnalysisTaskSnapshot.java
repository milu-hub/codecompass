package com.codecompass.service;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.codecompass.analyzer.AnalyzeResult;
import com.codecompass.graph.DependencyGraph;

/**
 * 分析任务的一次不可变快照。
 *
 * <p><b>状态与结果必须原子发布</b>：若先写 status=done 再写结果，轮询方会读到 done 却
 * 拿不到结果 —— 这个瞬时态只在并发下出现。因此结果聚合在 {@code outcome} 字段里，
 * "发布 done" 与"结果可见"是同一个 CAS 替换，结构上不存在中间态。
 *
 * <p>所有 {@code withX} 方法都是纯函数（不修改 this），供
 * {@link AnalysisTaskStore#update(String, java.util.function.UnaryOperator)} 的 CAS 重试安全使用。
 */
public record AnalysisTaskSnapshot(
        String taskId,
        String url,
        String status,
        String language,
        int progress,
        String message,
        String errorMessage,
        Instant createdAt,
        Instant updatedAt,
        AnalysisOutcome outcome) {

    public static final String STATUS_PENDING = "pending";
    public static final String STATUS_RUNNING = "running";
    public static final String STATUS_DONE = "done";
    public static final String STATUS_FAILED = "failed";

    public AnalysisTaskSnapshot withProgress(String newStatus, int newProgress, String newMessage) {
        return new AnalysisTaskSnapshot(taskId, url, newStatus, language, newProgress,
                newMessage, errorMessage, createdAt, Instant.now(), outcome);
    }

    public AnalysisTaskSnapshot withFailure(String reason) {
        return new AnalysisTaskSnapshot(taskId, url, STATUS_FAILED, language, progress,
                reason, reason, createdAt, Instant.now(), outcome);
    }

    public AnalysisTaskSnapshot withDone(AnalysisOutcome newOutcome, String detectedLanguage, String doneMessage) {
        return new AnalysisTaskSnapshot(taskId, url, STATUS_DONE, detectedLanguage, 100,
                doneMessage, null, createdAt, Instant.now(), newOutcome);
    }

    /**
     * 分析结果聚合。任务完成前为 null。
     *
     * <p>{@code sourceLines} 是 T7 决策 ② 的落地：分析完立即删工作区（§07 底线），
     * 删除前把源文件内容快照进内存，供 T9 检索与 T10 引用使用。
     */
    public record AnalysisOutcome(
            AnalyzeResult result,
            DependencyGraph graph,
            Map<String, String> roles,
            Map<String, List<String>> sourceLines) {

        public AnalysisOutcome {
            roles = roles == null ? Map.of() : Map.copyOf(roles);
            sourceLines = deepCopy(sourceLines);
        }

        private static Map<String, List<String>> deepCopy(Map<String, List<String>> lines) {
            if (lines == null) {
                return Map.of();
            }
            Map<String, List<String>> copy = new LinkedHashMap<>();
            lines.forEach((key, value) -> copy.put(key, value == null ? List.of() : List.copyOf(value)));
            return Map.copyOf(copy);
        }
    }
}
