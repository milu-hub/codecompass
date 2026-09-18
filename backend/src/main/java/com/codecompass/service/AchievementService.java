package com.codecompass.service;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import com.codecompass.persistence.AchievementEntity;
import com.codecompass.persistence.AchievementRepository;
import com.codecompass.persistence.UserActionEntity;
import com.codecompass.persistence.UserActionRepository;

/**
 * F5 成就服务：行为流水（user_actions）与解锁判定。
 *
 * <p>规则全部来自 {@link AchievementProperties}（配置）：按 (clientId, action)
 * 计数 ≥ threshold 且未解锁 → 解锁。record 以 refId 幂等（同一任务/测验不重复计数）。
 */
public class AchievementService {

    private final UserActionRepository userActions;
    private final AchievementRepository achievements;
    private final AchievementProperties properties;
    private final Clock clock;

    public AchievementService(UserActionRepository userActions, AchievementRepository achievements,
                              AchievementProperties properties, Clock clock) {
        this.userActions = userActions;
        this.achievements = achievements;
        this.properties = properties;
        this.clock = clock;
    }

    /** 记录一次行为（refId 幂等）并检查解锁，返回本次新解锁的成就。 */
    public List<AchievementView> record(String clientId, String action, String repoUrl, String refId) {
        if (clientId == null || clientId.isBlank() || action == null || action.isBlank()) {
            return List.of();
        }
        if (refId != null && !refId.isBlank()
                && userActions.existsByClientIdAndActionAndRefId(clientId, action, refId)) {
            return check(clientId, action);
        }
        userActions.save(new UserActionEntity(clientId, action, repoUrl, refId, clock.instant()));
        return check(clientId, action);
    }

    /** 按定义检查：计数达标且未解锁 → 解锁。 */
    public List<AchievementView> check(String clientId, String action) {
        if (clientId == null || clientId.isBlank()) {
            return List.of();
        }
        List<AchievementView> unlocked = new ArrayList<>();
        for (AchievementProperties.Definition definition : properties.getDefinitions()) {
            if (!definition.action().equals(action)) {
                continue;
            }
            long count = userActions.countByClientIdAndAction(clientId, action);
            if (count >= Math.max(1, definition.threshold())
                    && !achievements.existsByClientIdAndCode(clientId, definition.code())) {
                AchievementEntity entity = achievements.save(
                        new AchievementEntity(clientId, definition.code(), clock.instant()));
                unlocked.add(new AchievementView(definition.code(), definition.name(),
                        definition.description(), entity.getUnlockedAt()));
            }
        }
        return List.copyOf(unlocked);
    }

    /** 全部定义 + 解锁状态（未解锁 unlockedAt=null）—— GET /api/achievements 的响应。 */
    public List<AchievementView> unlocked(String clientId) {
        if (clientId == null || clientId.isBlank()) {
            return List.of();
        }
        return properties.getDefinitions().stream()
                .map(definition -> achievements.findByClientIdAndCode(clientId, definition.code())
                        .map(entity -> new AchievementView(definition.code(), definition.name(),
                                definition.description(), entity.getUnlockedAt()))
                        .orElseGet(() -> new AchievementView(definition.code(), definition.name(),
                                definition.description(), null)))
                .toList();
    }
}
