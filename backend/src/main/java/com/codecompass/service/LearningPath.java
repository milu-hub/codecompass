package com.codecompass.service;

import java.util.List;

/**
 * F2 学习路线：一次分析产出的推荐阅读顺序。
 *
 * <p>顺序由 {@link LearningPathService} 的确定性算法定（入口第一、被依赖多先读、
 * 同层字典序），LLM 只写每步 {@code reason}。
 */
public record LearningPath(String repoUrl, String commitSha, List<Step> steps) {

    public LearningPath {
        steps = steps == null ? List.of() : List.copyOf(steps);
    }

    public record Step(int order, String codeUnitId, String reason, int estimatedMinutes) {
    }
}
