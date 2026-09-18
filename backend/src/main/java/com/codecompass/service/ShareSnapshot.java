package com.codecompass.service;

import java.time.Instant;
import java.util.List;

/**
 * 分享快照内容（脱敏后）：
 * 仓库信息 + 依赖图 mermaid + 学习路线 + 前 10 条问答 + 已解锁成就。
 * 不含 API key、不含源码内容、不含他人笔记（问答只取分享者自己的）。
 */
public record ShareSnapshot(
        String repoUrl,
        String commitSha,
        Instant generatedAt,
        String graphMermaid,
        List<PathStep> learningPath,
        List<QaSample> qaSamples,
        List<AchievementBrief> achievements) {

    public record PathStep(int order, String codeUnitName, String reason, int estimatedMinutes) {
    }

    public record QaSample(String question, String answer, List<AnswerResponse.Reference> references) {
    }

    public record AchievementBrief(String code, String name) {
    }
}
