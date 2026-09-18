package com.codecompass.service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.codecompass.analyzer.AnalyzeResult;
import com.codecompass.analyzer.CodeUnitInfo;
import com.codecompass.analyzer.MethodInfo;

import tools.jackson.core.JacksonException;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * F2 学习路线生成：顺序由确定性算法定，LLM 只写每步 reason。
 *
 * <p><b>顺序（环安全）</b>：
 * <ol>
 *   <li>入口类（role=entry）按 unitName 字典序置顶；</li>
 *   <li>其余按「被依赖数（入度）降序、unitName 字典序」——被依赖多的先读、
 *       同层字典序。不用 Kahn 拓扑：类级依赖图可能有环，Kahn 遇环会丢节点。</li>
 * </ol>
 *
 * <p><b>LLM</b>：一次调用、只喂元数据（名字/角色/注解/方法签名/被依赖数），不传源码；
 * 返回 JSON 数组逐条校验 codeUnitId 并截断 60 字；失败/未配置时回退确定性描述，
 * 保证每步 reason 非空。
 */
public class LearningPathService {

    private static final Logger log = LoggerFactory.getLogger(LearningPathService.class);

    private static final int REASON_LIMIT = 60;

    private static final String SYSTEM_PROMPT = """
            你是代码讲解助手。根据给定类的元数据，为每个类写一句「为什么先读它」的中文 reason。
            规则：1) reason 不超过 60 字、不含换行；2) 不改变顺序；3) 只输出 JSON：
            {"reasons":[{"codeUnitId":"...","reason":"..."}]}，不要输出 JSON 之外的任何内容。
            """;

    private final LlmClient llmClient;
    private final JsonMapper lenientMapper;

    public LearningPathService(LlmClient llmClient, JsonMapper jsonMapper) {
        this.llmClient = llmClient;
        this.lenientMapper = jsonMapper.rebuild()
                .disable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
                .build();
    }

    public LearningPath generate(AnalyzeResult result, Map<String, String> roles,
                                 String repoUrl, String commitSha) {
        List<CodeUnitInfo> units = result.codeUnits();
        Map<String, Integer> inDegree = new HashMap<>();
        units.forEach(unit -> inDegree.put(unit.id(), 0));
        result.dependencies().forEach(edge -> {
            if (inDegree.containsKey(edge.toCodeUnitId())) {
                inDegree.merge(edge.toCodeUnitId(), 1, Integer::sum);
            }
        });

        List<CodeUnitInfo> ordered = new ArrayList<>();
        Set<String> used = new HashSet<>();
        units.stream()
                .filter(unit -> "entry".equals(roles.getOrDefault(unit.id(), "")))
                .sorted(Comparator.comparing(CodeUnitInfo::name))
                .forEach(unit -> {
                    ordered.add(unit);
                    used.add(unit.id());
                });
        units.stream()
                .filter(unit -> !used.contains(unit.id()))
                .sorted(Comparator
                        .comparingInt((CodeUnitInfo unit) -> inDegree.getOrDefault(unit.id(), 0)).reversed()
                        .thenComparing(CodeUnitInfo::name))
                .forEach(ordered::add);

        Map<String, String> reasons = generateReasons(ordered, roles, inDegree);
        Map<String, Integer> methodCount = methodCountByUnit(result);

        List<LearningPath.Step> steps = new ArrayList<>();
        for (int i = 0; i < ordered.size(); i++) {
            CodeUnitInfo unit = ordered.get(i);
            int degree = inDegree.getOrDefault(unit.id(), 0);
            String reason = reasons.getOrDefault(unit.id(), fallbackReason(degree));
            steps.add(new LearningPath.Step(i + 1, unit.id(),
                    clamp(reason, REASON_LIMIT), estimatedMinutes(methodCount.getOrDefault(unit.id(), 0))));
        }
        return new LearningPath(repoUrl, commitSha, steps);
    }

    // ---------- LLM reason ----------

    private Map<String, String> generateReasons(List<CodeUnitInfo> ordered,
                                                Map<String, String> roles,
                                                Map<String, Integer> inDegree) {
        try {
            String raw = llmClient.complete(SYSTEM_PROMPT, buildReasonPrompt(ordered, roles, inDegree));
            JsonNode root = lenientMapper.readTree(AnswerService.stripFences(raw));
            JsonNode array = root.get("reasons");
            Map<String, String> reasons = new HashMap<>();
            if (array != null && array.isArray()) {
                for (JsonNode node : array) {
                    String id = node.path("codeUnitId").isTextual()
                            ? node.path("codeUnitId").asText() : "";
                    String reason = node.path("reason").isTextual()
                            ? node.path("reason").asText() : "";
                    if (!id.isBlank() && !reason.isBlank()) {
                        reasons.put(id, reason);
                    }
                }
            }
            return reasons;
        } catch (LlmException | JacksonException e) {
            log.warn("LLM 生成学习路线 reason 失败，回退确定性描述：{}", e.getMessage());
            return Map.of();
        }
    }

    private static String buildReasonPrompt(List<CodeUnitInfo> ordered,
                                            Map<String, String> roles,
                                            Map<String, Integer> inDegree) {
        StringBuilder prompt = new StringBuilder("以下类已按推荐阅读顺序排列，请为每个类写 reason：\n");
        for (CodeUnitInfo unit : ordered) {
            prompt.append("id=").append(unit.id())
                    .append(", name=").append(unit.packageName()).append('.').append(unit.name())
                    .append(", role=").append(roles.getOrDefault(unit.id(), ""))
                    .append(", annotations=").append(unit.annotations())
                    .append(", 被依赖数=").append(inDegree.getOrDefault(unit.id(), 0))
                    .append('\n');
        }
        return prompt.toString();
    }

    private static String fallbackReason(int inDegree) {
        return inDegree > 0
                ? "被 " + inDegree + " 个类依赖，先读它建立基础概念"
                : "基础或叶子类，读它了解领域概念";
    }

    private static String clamp(String reason, int limit) {
        String trimmed = reason == null ? "" : reason.replaceAll("\\s+", " ").trim();
        return trimmed.length() <= limit ? trimmed : trimmed.substring(0, limit);
    }

    private static int estimatedMinutes(int methodCount) {
        return 5 + Math.min(methodCount, 10);
    }

    private static Map<String, Integer> methodCountByUnit(AnalyzeResult result) {
        Map<String, Integer> count = new HashMap<>();
        for (MethodInfo method : result.methods()) {
            count.merge(method.codeUnitId(), 1, Integer::sum);
        }
        return count;
    }
}
