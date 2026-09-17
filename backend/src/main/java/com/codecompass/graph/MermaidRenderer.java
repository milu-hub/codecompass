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
 */
public class MermaidRenderer {

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
        return out.toString();
    }

    private static String escapeLabel(String label) {
        if (label == null) {
            return "";
        }
        return label.replace('"', '\'');
    }
}
