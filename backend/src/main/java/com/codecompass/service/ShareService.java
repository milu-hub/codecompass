package com.codecompass.service;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

import com.codecompass.analyzer.AnalyzeResult;
import com.codecompass.persistence.LearningPathEntity;
import com.codecompass.persistence.LearningPathRepository;
import com.codecompass.persistence.QaHistoryEntity;
import com.codecompass.persistence.QaHistoryRepository;

import tools.jackson.databind.json.JsonMapper;

/**
 * F6 分享快照构建：只取公开、脱敏的信息 ——
 * 依赖图 mermaid（无源码）、学习路线（reason 而已）、分享者自己的前 10 条问答
 * （answer 与引用，无源码内容）、成就的 code+name（无 clientId/unlockedAt）。
 */
public class ShareService {

    private final QaHistoryRepository qaHistoryRepository;
    private final LearningPathRepository learningPathRepository;
    private final JsonMapper jsonMapper;
    private final Clock clock;
    private final ShareProperties properties;

    public ShareService(QaHistoryRepository qaHistoryRepository,
                        LearningPathRepository learningPathRepository,
                        JsonMapper jsonMapper, Clock clock, ShareProperties properties) {
        this.qaHistoryRepository = qaHistoryRepository;
        this.learningPathRepository = learningPathRepository;
        this.jsonMapper = jsonMapper;
        this.clock = clock;
        this.properties = properties;
    }

    public ShareSnapshot build(AnalyzeResult result, String graphMermaid,
                               String repoUrl, String commitSha,
                               String clientId, List<AchievementView> achievements) {
        Map<String, String> unitNames = result.codeUnits().stream()
                .collect(Collectors.toMap(unit -> unit.id(), unit -> unit.name()));

        List<ShareSnapshot.PathStep> pathSteps = new ArrayList<>();
        LearningPathEntity path = learningPathRepository
                .findFirstByRepoUrlOrderByCreatedAtDesc(repoUrl).orElse(null);
        if (path != null) {
            try {
                LearningPath learningPath = jsonMapper.readValue(path.getPathJson(), LearningPath.class);
                pathSteps = learningPath.steps().stream()
                        .map(step -> new ShareSnapshot.PathStep(step.order(),
                                unitNames.getOrDefault(step.codeUnitId(), step.codeUnitId()),
                                step.reason(), step.estimatedMinutes()))
                        .toList();
            } catch (Exception ignored) {
                // 学习路线 JSON 损坏时分享页不带路线
            }
        }

        List<ShareSnapshot.QaSample> qaSamples = new ArrayList<>();
        for (QaHistoryEntity history : qaHistoryRepository
                .findTop10ByClientIdAndRepoUrlOrderByCreatedAtDesc(clientId, repoUrl)) {
            try {
                AnswerResponse answer = jsonMapper.readValue(history.getAnswerJson(), AnswerResponse.class);
                qaSamples.add(new ShareSnapshot.QaSample(history.getQuestion(),
                        answer.answer(), answer.references()));
            } catch (Exception ignored) {
                // 单条历史损坏跳过
            }
        }

        List<ShareSnapshot.AchievementBrief> briefs = achievements == null ? List.of()
                : achievements.stream()
                        .filter(achievement -> achievement.unlockedAt() != null)
                        .map(achievement -> new ShareSnapshot.AchievementBrief(
                                achievement.code(), achievement.name()))
                        .toList();

        return new ShareSnapshot(repoUrl, commitSha, clock.instant(),
                graphMermaid, pathSteps, qaSamples, briefs);
    }

    /** 短链 id：12 位 hex。 */
    public String newShareId() {
        return UUID.randomUUID().toString().replace("-", "").substring(0, 12);
    }

    public Instant expiresAt() {
        return clock.instant().plusSeconds(properties.getExpiresDays() * 24L * 3600L);
    }
}
