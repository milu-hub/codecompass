package com.codecompass.service;

import java.time.Clock;
import java.time.Instant;
import java.util.List;

import com.codecompass.persistence.LearningPathEntity;
import com.codecompass.persistence.LearningPathRepository;
import com.codecompass.persistence.ProgressEntity;
import com.codecompass.persistence.ProgressRepository;

import tools.jackson.databind.json.JsonMapper;

/**
 * F5 阅读进度：upsert + 查询 + PATH_FINISHED 触发（全部学习路线步骤标 done 时解锁）。
 */
public class ProgressService {

    private static final List<String> VALID_STATUS = List.of("unread", "reading", "done");

    private final ProgressRepository repository;
    private final LearningPathRepository learningPathRepository;
    private final AchievementService achievementService;
    private final JsonMapper jsonMapper;
    private final Clock clock;

    public ProgressService(ProgressRepository repository, LearningPathRepository learningPathRepository,
                           AchievementService achievementService, JsonMapper jsonMapper, Clock clock) {
        this.repository = repository;
        this.learningPathRepository = learningPathRepository;
        this.achievementService = achievementService;
        this.jsonMapper = jsonMapper;
        this.clock = clock;
    }

    public Progress update(String clientId, String repoUrl, String codeUnitId, String status) {
        if (!VALID_STATUS.contains(status)) {
            throw new IllegalArgumentException("status 必须是 unread / reading / done 之一");
        }
        Instant now = clock.instant();
        ProgressEntity entity = repository
                .findByClientIdAndRepoUrlAndCodeUnitId(clientId, repoUrl, codeUnitId)
                .map(existing -> {
                    existing.setStatus(status);
                    existing.setUpdatedAt(now);
                    return existing;
                })
                .orElseGet(() -> new ProgressEntity(clientId, repoUrl, codeUnitId, status, now));
        repository.save(entity);

        if ("done".equals(status)) {
            maybeUnlockPathFinished(clientId, repoUrl);
        }
        return new Progress(clientId, repoUrl, codeUnitId, entity.getStatus(), entity.getUpdatedAt());
    }

    public List<Progress> list(String clientId, String repoUrl) {
        return repository.findByClientIdAndRepoUrl(clientId, repoUrl).stream()
                .map(entity -> new Progress(entity.getClientId(), entity.getRepoUrl(),
                        entity.getCodeUnitId(), entity.getStatus(), entity.getUpdatedAt()))
                .toList();
    }

    /** PATH_FINISHED：该仓库最新学习路线的全部步骤都已 done。 */
    private void maybeUnlockPathFinished(String clientId, String repoUrl) {
        LearningPathEntity path = learningPathRepository
                .findFirstByRepoUrlOrderByCreatedAtDesc(repoUrl).orElse(null);
        if (path == null) {
            return;
        }
        try {
            LearningPath learningPath = jsonMapper.readValue(path.getPathJson(), LearningPath.class);
            boolean allDone = learningPath.steps().stream().allMatch(step ->
                    repository.findByClientIdAndRepoUrlAndCodeUnitId(clientId, repoUrl, step.codeUnitId())
                            .map(entity -> "done".equals(entity.getStatus()))
                            .orElse(false));
            if (allDone) {
                achievementService.record(clientId, "path_done", repoUrl, repoUrl);
            }
        } catch (Exception ignored) {
            // 学习路线 JSON 损坏时放弃解锁，不影响进度主流程
        }
    }
}
