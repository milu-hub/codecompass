package com.codecompass.graph;

import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.codecompass.analyzer.DependencyEdge;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Mermaid 渲染。
 *
 * 关键约束是**节点 ID 必须重新映射**：真实的 {@code CodeUnitInfo.id} 形如
 * {@code repo:src/main/java/...#org.springframework...OwnerController}，含 `:` `/` `.` `#`，
 * 不在 Mermaid 的 ID 语法内，直接使用会静默产出异常节点。
 */
class MermaidRendererTest {

    private final MermaidRenderer renderer = new MermaidRenderer();

    private static GraphNode node(String id, String label) {
        return new GraphNode(id, label, "com.example." + label, "class", "", "f.java", 1, 9);
    }

    private static DependencyEdge edge(String fromId, String toId) {
        return new DependencyEdge(fromId + "->" + toId, "repo-1", fromId, toId, "field", "java");
    }

    @Test
    @DisplayName("渲染节点与边：ID 被映射成短名，可读名放进带引号的 label")
    void rendersNodesAndEdgesWithSafeIds() {
        String rawId = "repo:src/main/java/A.java#com.example.OwnerController";
        String otherId = "repo:src/main/java/B.java#com.example.OwnerRepository";

        String mermaid = renderer.render(
                List.of(node(rawId, "OwnerController"), node(otherId, "OwnerRepository")),
                List.of(edge(rawId, otherId)));

        assertThat(mermaid).startsWith("graph LR");
        assertThat(mermaid).contains("n0[\"OwnerController\"]");
        assertThat(mermaid).contains("n1[\"OwnerRepository\"]");
        assertThat(mermaid).contains("n0 --> n1");
        assertThat(mermaid)
                .as("原始 id 含 Mermaid 不接受的字符，不得出现在图里")
                .doesNotContain("#com.example")
                .doesNotContain("/");
    }

    @Test
    @DisplayName("ID 分配与输入顺序无关：先按 id 排序再编号")
    void idAssignmentIsOrderIndependent() {
        GraphNode a = node("id-a", "Alpha");
        GraphNode b = node("id-b", "Beta");

        String forward = renderer.render(List.of(a, b), List.of(edge("id-a", "id-b")));
        String reversed = renderer.render(List.of(b, a), List.of(edge("id-a", "id-b")));

        assertThat(forward)
                .as("同一份图两次渲染必须完全一致，否则前端 diff 与快照测试全废")
                .isEqualTo(reversed);
    }

    @Test
    @DisplayName("空图降级为占位节点——只输出 graph LR 时 Mermaid 渲染不可靠")
    void emptyGraphRendersPlaceholder() {
        String mermaid = renderer.render(List.of(), List.of());

        assertThat(mermaid).startsWith("graph LR");
        assertThat(mermaid.lines().count())
                .as("不能只有一行 graph LR，必须给出可渲染的占位内容")
                .isGreaterThan(1);
    }

    @Test
    @DisplayName("没有边但有节点时不降级——节点本身就该画出来")
    void nodesWithoutEdgesStillRender() {
        String mermaid = renderer.render(List.of(node("id-a", "Alpha")), List.of());

        assertThat(mermaid).contains("n0[\"Alpha\"]");
    }

    @Test
    @DisplayName("label 一律加引号，避免特殊字符破坏语法")
    void labelsAreAlwaysQuoted() {
        String mermaid = renderer.render(List.of(node("id-a", "Weird Name")), List.of());

        assertThat(mermaid).contains("[\"Weird Name\"]");
    }

    @Test
    @DisplayName("边指向未声明的节点时不渲染它——Mermaid 会自动创建无标签幽灵节点")
    void doesNotEmitEdgesToUndeclaredNodes() {
        String mermaid = renderer.render(
                List.of(node("id-a", "Alpha")),
                List.of(edge("id-a", "id-missing")));

        assertThat(mermaid)
                .as("渲染器只画声明过的节点，悬空边必须在更早的图构建阶段被过滤")
                .doesNotContain("-->");
    }

    // ---------- T24：角色配色 ----------

    private static GraphNode roleNode(String id, String label, String role) {
        return new GraphNode(id, label, "com.example." + label, "class", role, "f.java", 1, 9);
    }

    @Test
    @DisplayName("按角色输出 classDef + class：entry 橙 / controller 蓝；未出现的角色不输出")
    void colorsNodesByRole() {
        String mermaid = renderer.render(List.of(
                roleNode("id-a", "App", "entry"),
                roleNode("id-b", "Ctrl", "controller"),
                node("id-c", "Plain")), List.of());

        assertThat(mermaid)
                .contains("classDef role_entry fill:#fff3e0,stroke:#f57c00,color:#1f2a27")
                .contains("class n0 role_entry")
                .contains("classDef role_controller fill:#e3f2fd,stroke:#1976d2,color:#1f2a27")
                .contains("class n1 role_controller");
        assertThat(mermaid)
                .as("浅底配深描边，文字必须是墨色——白字压在浅色填充上不可读")
                .doesNotContain("color:#ffffff");
        assertThat(mermaid)
                .as("未出现的角色不输出 classDef")
                .doesNotContain("role_entity")
                .doesNotContain("role_mapper");
        assertThat(mermaid)
                .as("无角色节点不挂 class")
                .doesNotContain("class n2");
    }

    @Test
    @DisplayName("六个角色都有浅底/深描边成对配色（规格外的 repository 按同族补青色）")
    void everyRoleHasPastelPalette() {
        String mermaid = renderer.render(List.of(
                roleNode("id-a", "A", "entry"),
                roleNode("id-b", "B", "controller"),
                roleNode("id-c", "C", "service"),
                roleNode("id-d", "D", "entity"),
                roleNode("id-e", "E", "mapper"),
                roleNode("id-f", "F", "repository")), List.of());

        assertThat(mermaid)
                .contains("role_service fill:#e8f5e9,stroke:#388e3c")
                .contains("role_entity fill:#f5f5f5,stroke:#9e9e9e")
                .contains("role_mapper fill:#f3e5f5,stroke:#8e24aa")
                .contains("role_repository fill:#e0f7fa,stroke:#0097a7");
    }

    @Test
    @DisplayName("同角色多节点合并到一条 class 行（逗号分隔，按 id 顺序）")
    void mergesSameRoleNodes() {
        String mermaid = renderer.render(List.of(
                roleNode("id-a", "A", "service"),
                roleNode("id-b", "B", "service")), List.of());

        assertThat(mermaid).contains("class n0,n1 role_service");
    }

    @Test
    @DisplayName("带角色时渲染仍确定性：输入顺序无关")
    void roleRenderingIsDeterministic() {
        GraphNode a = roleNode("id-a", "A", "entry");
        GraphNode b = roleNode("id-b", "B", "service");

        assertThat(renderer.render(List.of(a, b), List.of()))
                .isEqualTo(renderer.render(List.of(b, a), List.of()));
    }
}
