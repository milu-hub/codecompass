package com.codecompass.graph;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.codecompass.analyzer.AnalyzeResult;
import com.codecompass.analyzer.CodeUnitInfo;
import com.codecompass.analyzer.DependencyEdge;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 依赖图构建：从分析结果整理出语言中立的图结构。
 *
 * 本类不认识任何语言或框架 —— 角色是从入参 Map 拿的，不是注入 T5 的分类器
 * （那个在 analyzer/java/ 里，是 Spring 概念）。
 */
class DependencyGraphBuilderTest {

    private final DependencyGraphBuilder builder =
            new DependencyGraphBuilder(new MermaidRenderer());

    private static CodeUnitInfo unit(String name) {
        return new CodeUnitInfo("repo:" + name, "repo-1", "src/main/java/" + name + ".java",
                "java", "spring", "com.example", name, "class", List.of(), List.of(), 1, 20);
    }

    private static DependencyEdge edge(String fromName, String toName) {
        return new DependencyEdge("repo:" + fromName + "->repo:" + toName, "repo-1",
                "repo:" + fromName, "repo:" + toName, "field", "java");
    }

    private static AnalyzeResult result(List<CodeUnitInfo> units, List<DependencyEdge> edges) {
        return new AnalyzeResult("repo-1", "java", "spring", units, List.of(), edges, List.of());
    }

    // ---------- 节点 ----------

    @Test
    @DisplayName("节点携带展示所需的全部信息，label 用简单名而不是全限定名")
    void buildsNodesFromCodeUnits() {
        AnalyzeResult result = result(
                List.of(unit("OwnerController"), unit("OwnerRepository")),
                List.of(edge("OwnerController", "OwnerRepository")));

        DependencyGraph graph = builder.build(result, Map.of("repo:OwnerController", "controller"));

        assertThat(graph.nodes()).hasSize(2);
        GraphNode controller = graph.nodes().stream()
                .filter(node -> node.label().equals("OwnerController")).findFirst().orElseThrow();
        assertThat(controller.id()).isEqualTo("repo:OwnerController");
        assertThat(controller.qualifiedName()).isEqualTo("com.example.OwnerController");
        assertThat(controller.kind()).isEqualTo("class");
        assertThat(controller.role()).isEqualTo("controller");
        assertThat(controller.filePath()).isEqualTo("src/main/java/OwnerController.java");
        assertThat(controller.startLine()).isEqualTo(1);
        assertThat(graph.language()).isEqualTo("java");
        assertThat(graph.framework()).isEqualTo("spring");
    }

    @Test
    @DisplayName("没有角色的节点 role 为空串，不是 null")
    void missingRoleBecomesEmptyString() {
        AnalyzeResult result = result(
                List.of(unit("A"), unit("B")), List.of(edge("A", "B")));

        assertThat(builder.build(result, Map.of()).nodes())
                .allSatisfy(node -> assertThat(node.role()).isEmpty());
    }

    // ---------- 孤立节点 ----------

    @Test
    @DisplayName("默认不把孤立节点放进图，但完整报告它们的 id")
    void excludesIsolatedNodesButReportsThem() {
        AnalyzeResult result = result(
                List.of(unit("A"), unit("B"), unit("Lonely"), unit("AlsoLonely")),
                List.of(edge("A", "B")));

        DependencyGraph graph = builder.build(result, Map.of());

        assertThat(graph.nodes()).extracting(GraphNode::label)
                .as("实测 microservices 有 41% 的单元没有边，全画出来会散落一堆孤立方框")
                .containsExactly("A", "B");
        assertThat(graph.isolatedCodeUnitIds())
                .containsExactlyInAnyOrder("repo:Lonely", "repo:AlsoLonely");
    }

    @Test
    @DisplayName("全部孤立时得到空图，但仍然可渲染")
    void allIsolatedYieldsRenderableEmptyGraph() {
        AnalyzeResult result = result(List.of(unit("A"), unit("B")), List.of());

        DependencyGraph graph = builder.build(result, Map.of());

        assertThat(graph.nodes()).isEmpty();
        assertThat(graph.isolatedCodeUnitIds()).hasSize(2);
        assertThat(graph.mermaid()).as("空图也要能渲染").isNotBlank();
    }

    // ---------- 引用完整性 ----------

    @Test
    @DisplayName("端点不存在的边被丢弃——否则 Mermaid 会造出无标签幽灵节点")
    void dropsEdgesWithMissingEndpoints() {
        AnalyzeResult result = result(
                List.of(unit("A"), unit("B")),
                List.of(edge("A", "B"), edge("A", "Ghost")));

        DependencyGraph graph = builder.build(result, Map.of());

        assertThat(graph.edges()).hasSize(1);
        assertThat(graph.edges().get(0).toCodeUnitId()).isEqualTo("repo:B");
        assertThat(graph.nodes()).extracting(GraphNode::label).containsExactly("A", "B");
    }

    @Test
    @DisplayName("每条边的两端都能在 nodes 里找到")
    void everyEdgeEndpointIsPresentInNodes() {
        AnalyzeResult result = result(
                List.of(unit("A"), unit("B"), unit("C")),
                List.of(edge("A", "B"), edge("B", "C"), edge("C", "A"), edge("A", "Ghost")));

        DependencyGraph graph = builder.build(result, Map.of());

        List<String> nodeIds = graph.nodes().stream().map(GraphNode::id).toList();
        assertThat(graph.edges()).isNotEmpty();
        assertThat(graph.edges()).allSatisfy(edge -> {
            assertThat(nodeIds).contains(edge.fromCodeUnitId());
            assertThat(nodeIds).contains(edge.toCodeUnitId());
        });
    }

    // ---------- 确定性 ----------

    @Test
    @DisplayName("输入顺序打乱后产出完全一致——节点的排序决定 Mermaid 的 ID 分配")
    void outputIsIndependentOfInputOrder() {
        AnalyzeResult forward = result(
                List.of(unit("Alpha"), unit("Beta"), unit("Gamma")),
                List.of(edge("Alpha", "Beta"), edge("Beta", "Gamma")));
        AnalyzeResult reversed = result(
                List.of(unit("Gamma"), unit("Beta"), unit("Alpha")),
                List.of(edge("Beta", "Gamma"), edge("Alpha", "Beta")));

        DependencyGraph first = builder.build(forward, Map.of());
        DependencyGraph second = builder.build(reversed, Map.of());

        assertThat(first.nodes()).extracting(GraphNode::id)
                .containsExactlyElementsOf(second.nodes().stream().map(GraphNode::id).toList());
        assertThat(first.mermaid()).isEqualTo(second.mermaid());
    }

    @Test
    @DisplayName("构建结果里带有可直接渲染的 mermaid 文本")
    void includesRenderedMermaid() {
        AnalyzeResult result = result(
                List.of(unit("OwnerController"), unit("OwnerRepository")),
                List.of(edge("OwnerController", "OwnerRepository")));

        String mermaid = builder.build(result, Map.of()).mermaid();

        assertThat(mermaid).startsWith("graph LR").contains("-->");
    }

    // ---------- 邻域 ----------

    @Test
    @DisplayName("取邻域：以某节点为中心的一跳含入边与出边两端")
    void neighborhoodContainsBothDirections() {
        AnalyzeResult result = result(
                List.of(unit("Up"), unit("Center"), unit("Down"), unit("Far")),
                List.of(edge("Up", "Center"), edge("Center", "Down"), edge("Down", "Far")));
        DependencyGraph graph = builder.build(result, Map.of());

        DependencyGraph neighborhood = builder.neighborhoodOf(graph, "repo:Center", 1);

        assertThat(neighborhood.nodes()).extracting(GraphNode::label)
                .containsExactlyInAnyOrder("Up", "Center", "Down");
        assertThat(neighborhood.edges()).hasSize(2);
    }

    @Test
    @DisplayName("取邻域：两跳能把更远的节点带进来")
    void neighborhoodRespectsDepth() {
        AnalyzeResult result = result(
                List.of(unit("Up"), unit("Center"), unit("Down"), unit("Far")),
                List.of(edge("Up", "Center"), edge("Center", "Down"), edge("Down", "Far")));
        DependencyGraph graph = builder.build(result, Map.of());

        assertThat(builder.neighborhoodOf(graph, "repo:Center", 2).nodes())
                .extracting(GraphNode::label)
                .containsExactlyInAnyOrder("Up", "Center", "Down", "Far");
    }

    @Test
    @DisplayName("取邻域后引用完整性依然成立")
    void neighborhoodKeepsReferentialIntegrity() {
        AnalyzeResult result = result(
                List.of(unit("Up"), unit("Center"), unit("Down"), unit("Far")),
                List.of(edge("Up", "Center"), edge("Center", "Down"), edge("Down", "Far")));
        DependencyGraph graph = builder.build(result, Map.of());

        DependencyGraph neighborhood = builder.neighborhoodOf(graph, "repo:Center", 1);

        List<String> nodeIds = neighborhood.nodes().stream().map(GraphNode::id).toList();
        assertThat(neighborhood.edges()).allSatisfy(edge -> {
            assertThat(nodeIds).contains(edge.fromCodeUnitId());
            assertThat(nodeIds).contains(edge.toCodeUnitId());
        });
        assertThat(neighborhood.mermaid()).isNotBlank();
    }

    @Test
    @DisplayName("孤立类不在图里，取它的邻域得到空图——前端应据此显示「没有依赖」")
    void neighborhoodOfUnknownNodeIsEmpty() {
        AnalyzeResult result = result(
                List.of(unit("A"), unit("B"), unit("Lonely")), List.of(edge("A", "B")));
        DependencyGraph graph = builder.build(result, Map.of());

        DependencyGraph neighborhood = builder.neighborhoodOf(graph, "repo:Lonely", 1);

        assertThat(neighborhood.nodes()).isEmpty();
        assertThat(neighborhood.mermaid()).isNotBlank();
    }
}
