package com.codecompass.service;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import com.codecompass.analyzer.AnalyzeResult;
import com.codecompass.analyzer.CodeUnitInfo;
import com.codecompass.analyzer.DependencyEdge;

import tools.jackson.databind.json.JsonMapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.when;

/**
 * F2 学习路线顺序算法（环安全）与 LLM reason 降级。
 */
class LearningPathServiceTest {

    private LlmClient llmClient;
    private LearningPathService service;

    @BeforeEach
    void setUp() {
        llmClient = Mockito.mock(LlmClient.class);
        // 缺省：LLM 未配置（抛异常）→ 走 fallback reason
        when(llmClient.complete(anyString(), anyString()))
                .thenThrow(new LlmException("LLM 未配置"));
        service = new LearningPathService(llmClient, JsonMapper.builder().build());
    }

    private static CodeUnitInfo unit(String id, String name) {
        return new CodeUnitInfo(id, "r", "src/" + name + ".java", "java", "spring",
                "com.example", name, "class", List.of(), List.of(), 1, 10);
    }

    private static DependencyEdge edge(String from, String to) {
        return new DependencyEdge(from + "->" + to, "r", from, to, "field", "java");
    }

    private static AnalyzeResult result() {
        CodeUnitInfo entryA = unit("entry-a", "ApplicationA");
        CodeUnitInfo entryB = unit("entry-b", "ApplicationB");
        CodeUnitInfo core = unit("core", "CoreService");
        CodeUnitInfo mid = unit("mid", "MidService");
        CodeUnitInfo leaf = unit("leaf", "LeafUtil");
        CodeUnitInfo l1 = unit("l1", "Controller1");
        CodeUnitInfo l2 = unit("l2", "Controller2");
        CodeUnitInfo l3 = unit("l3", "Controller3");
        List<DependencyEdge> edges = List.of(
                edge("l1", "core"), edge("l2", "core"), edge("l3", "core"),
                edge("leaf", "mid"));
        return new AnalyzeResult("r", "java", "spring",
                List.of(entryA, entryB, core, mid, leaf, l1, l2, l3),
                List.of(), edges, List.of());
    }

    private static final Map<String, String> ROLES =
            Map.of("entry-a", "entry", "entry-b", "entry");

    @Test
    @DisplayName("入口类按名字典序置顶")
    void entriesComeFirstSortedByName() {
        LearningPath path = service.generate(result(), ROLES, "repo", "sha");

        assertThat(path.steps().get(0).codeUnitId()).isEqualTo("entry-a");
        assertThat(path.steps().get(1).codeUnitId()).isEqualTo("entry-b");
    }

    @Test
    @DisplayName("入口类之后按被依赖数（入度）降序：Core(3) 先于 Mid(1) 先于叶子(0)")
    void inDegreeDescendingAfterEntries() {
        LearningPath path = service.generate(result(), ROLES, "repo", "sha");

        List<String> ids = path.steps().stream().map(LearningPath.Step::codeUnitId).toList();
        assertThat(ids).containsSubsequence("core", "mid");
        assertThat(ids.indexOf("core")).isLessThan(ids.indexOf("leaf"));
    }

    @Test
    @DisplayName("不重复不遗漏：覆盖全部 8 个单元，order 连续 1..N")
    void coversAllUnitsExactlyOnce() {
        LearningPath path = service.generate(result(), ROLES, "repo", "sha");

        assertThat(path.steps()).hasSize(8);
        assertThat(path.steps().stream().map(LearningPath.Step::codeUnitId))
                .containsExactlyInAnyOrder(
                        "entry-a", "entry-b", "core", "mid", "leaf", "l1", "l2", "l3");
        for (int i = 0; i < path.steps().size(); i++) {
            assertThat(path.steps().get(i).order()).isEqualTo(i + 1);
        }
    }

    @Test
    @DisplayName("LLM 未配置：每步 reason 回退为确定性非空描述")
    void fallbackReasonIsNonEmpty() {
        LearningPath path = service.generate(result(), ROLES, "repo", "sha");

        assertThat(path.steps()).allSatisfy(step ->
                assertThat(step.reason()).isNotBlank());
        assertThat(path.steps().stream()
                .filter(step -> step.codeUnitId().equals("core"))
                .findFirst().orElseThrow().reason()).contains("被 3 个类依赖");
    }

    @Test
    @DisplayName("LLM 返回的 reason 超长时截断到 60 字")
    void llmReasonIsClampedToSixtyChars() {
        // doReturn 覆盖 setUp 里的 thenThrow（when().thenReturn 会先触发已注册的抛异常桩）
        doReturn("{\"reasons\":[{\"codeUnitId\":\"core\",\"reason\":\""
                + "很".repeat(100) + "\"}]}")
                .when(llmClient).complete(anyString(), anyString());

        LearningPath path = service.generate(result(), ROLES, "repo", "sha");

        LearningPath.Step core = path.steps().stream()
                .filter(step -> step.codeUnitId().equals("core")).findFirst().orElseThrow();
        assertThat(core.reason()).hasSize(60);
    }

    @Test
    @DisplayName("同输入两次生成，顺序一致（确定性）")
    void orderIsDeterministic() {
        LearningPath first = service.generate(result(), ROLES, "repo", "sha");
        LearningPath second = service.generate(result(), ROLES, "repo", "sha");

        assertThat(second.steps()).isEqualTo(first.steps());
    }
}
