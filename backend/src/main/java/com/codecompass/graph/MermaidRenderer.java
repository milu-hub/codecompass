package com.codecompass.graph;

import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.codecompass.analyzer.DependencyEdge;

/**
 * 把语言中立的图渲染成 Mermaid 文本。
 *
 * <p><b>节点 ID 必须重新映射</b>：真实单元 id 形如
 * {@code repo:src/main/java/...#org.springframework...OwnerController}，含 `:` `/` `.` `#`，
 * 不在 Mermaid 的 ID 语法内，直接使用会静默产出异常节点。故映射为 {@code n0, n1, ...}，
 * 可读名放进带引号的 label。
 *
 * <p><b>ID 分配必须确定性</b>：先按单元 id 排序再编号，与输入顺序无关 —— 同一份图两次
 * 渲染必须逐字节一致，否则前端 diff 与快照测试全废。
 *
 * <p><b>空图降级</b>：只有 {@code graph LR} 一行时 Mermaid 渲染不可靠，故空图输出占位节点。
 *
 * <p><b>T24 角色配色</b>：按节点角色输出 {@code classDef} + {@code class} 行。
 * 第六步改版：从「实色块 + 白字」改成「浅色填充 + 同族深色描边 + 墨色文字」——
 * 浅底配深描边在大图里更容易分辨角色，墨字也保证可读（白字压在浅底上根本看不见）。
 * 配色顺序与节点顺序都固定，保持渲染确定性。
 */
public class MermaidRenderer {

    /** 角色 → 样式（浅填充 / 同族深描边 / 墨色文字）。顺序即 classDef 输出顺序（确定性）。 */
    private static final Map<String, String> ROLE_STYLES = new LinkedHashMap<>();

    static {
        ROLE_STYLES.put("entry", "fill:#fff3e0,stroke:#f57c00,color:#1f2a27");
        ROLE_STYLES.put("controller", "fill:#e3f2fd,stroke:#1976d2,color:#1f2a27");
        ROLE_STYLES.put("service", "fill:#e8f5e9,stroke:#388e3c,color:#1f2a27");
        ROLE_STYLES.put("entity", "fill:#f5f5f5,stroke:#9e9e9e,color:#1f2a27");
        ROLE_STYLES.put("mapper", "fill:#f3e5f5,stroke:#8e24aa,color:#1f2a27");
        // 规格里没给 repository，按同族规则补一对青色（cyan 50 / cyan 700）
        ROLE_STYLES.put("repository", "fill:#e0f7fa,stroke:#0097a7,color:#1f2a27");
    }

    public String render(List<GraphNode> nodes, List<DependencyEdge> edges) {
        List<GraphNode> sorted = nodes == null ? List.of()
                : nodes.stream().sorted(Comparator.comparing(GraphNode::id)).toList();

        if (sorted.isEmpty()) {
            return "graph LR\n  empty[\"该范围内没有依赖关系\"]\n";
        }

        Map<String, String> idByUnitId = new LinkedHashMap<>();
        int index = 0;
        for (GraphNode node : sorted) {
            idByUnitId.put(node.id(), "n" + index++);
        }

        StringBuilder out = new StringBuilder("graph LR\n");
        for (GraphNode node : sorted) {
            out.append("  ").append(idByUnitId.get(node.id()))
                    .append("[\"").append(escapeLabel(node.label())).append("\"]\n");
        }
        if (edges != null) {
            for (DependencyEdge edge : edges) {
                String from = idByUnitId.get(edge.fromCodeUnitId());
                String to = idByUnitId.get(edge.toCodeUnitId());
                if (from == null || to == null) {
                    // 端点未声明的边不渲染 —— Mermaid 会给它造一个无标签幽灵节点
                    continue;
                }
                out.append("  ").append(from).append(" --> ").append(to).append("\n");
            }
        }
        appendRoleStyles(out, sorted, idByUnitId);
        return out.toString();
    }

    /** 角色配色：只对出现过的角色输出 classDef，节点 class 行按 id 顺序（确定性）。 */
    private static void appendRoleStyles(StringBuilder out, List<GraphNode> sorted,
                                         Map<String, String> idByUnitId) {
        ROLE_STYLES.forEach((role, style) -> {
            List<String> nodeIds = sorted.stream()
                    .filter(node -> role.equals(node.role()))
                    .map(node -> idByUnitId.get(node.id()))
                    .toList();
            if (nodeIds.isEmpty()) {
                return;
            }
            String className = "role_" + role;
            out.append("  classDef ").append(className).append(' ').append(style).append('\n');
            out.append("  class ").append(String.join(",", nodeIds))
                    .append(' ').append(className).append('\n');
        });
    }

    private static String escapeLabel(String label) {
        if (label == null) {
            return "";
        }
        return label.replace('"', '\'');
    }
}
