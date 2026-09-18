package com.codecompass.service;

import java.time.Instant;
import java.util.List;

/**
 * 分享快照内容（脱敏后）：
 * 仓库信息 + 依赖图 mermaid + 学习路线 + 前 10 条问答 + 已解锁成就 + **分享者本人的笔记**。
 *
 * <p>不含 API key、不含源码内容、不含**他人**笔记（问答与笔记都只取分享者自己的，
 * 按 clientId 过滤 —— FEATURE_SPEC F6 的输入含「可选的笔记」，禁止项针对的是他人笔记）。
 */
public record ShareSnapshot(
        String repoUrl,
        String commitSha,
        Instant generatedAt,
        String graphMermaid,
        List<PathStep> learningPath,
        List<QaSample> qaSamples,
        List<AchievementBrief> achievements,
        List<NoteBrief> notes) {

    public ShareSnapshot {
        learningPath = learningPath == null ? List.of() : List.copyOf(learningPath);
        qaSamples = qaSamples == null ? List.of() : List.copyOf(qaSamples);
        achievements = achievements == null ? List.of() : List.copyOf(achievements);
        // 旧快照 JSON 里没有 notes 字段 → 规范化为空列表，反序列化不炸
        notes = notes == null ? List.of() : List.copyOf(notes);
    }

    public record PathStep(int order, String codeUnitName, String reason, int estimatedMinutes) {
    }

    public record QaSample(String question, String answer, List<AnswerResponse.Reference> references) {
    }

    public record AchievementBrief(String code, String name) {
    }

    /** 笔记摘要：类名 + 内容 + 更新时间（不含 clientId —— 分享页不继承任何身份信息）。 */
    public record NoteBrief(String codeUnitName, String content, Instant updatedAt) {
    }
}
