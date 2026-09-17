package com.codecompass.graph;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.codecompass.analyzer.AnalyzeResult;
import com.codecompass.analyzer.CodeUnitInfo;
import com.codecompass.analyzer.DependencyEdge;

/**
 * 从分析结果构建语言中立的类级依赖图。
 *
 * <p>本类不认识任何语言或框架 —— {@code roleByCodeUnitId} 由调用方提供（例如 T5 的分类器
 * 产出），这里只看到「单元 id → 一个标签」。注入 T5 分类器会让本包依赖 {@code analyzer/java/}
 * 里的 Spring 概念，R9 的隔离当场失守。
 *
 * <p>名字解析与边去重由 T4 完成，这里**不重复**：只做过滤、孤立识别、排序与渲染。
 */
public class DependencyGraphBuilder {

    private static final Logger log = LoggerFactory.getLogger(DependencyGraphBuilder.class);

    private final MermaidRenderer renderer;

    public DependencyGraphBuilder(MermaidRenderer renderer) {
        this.renderer = renderer;
    }

    public DependencyGraph build(AnalyzeResult result, Map<String, String> roleByCodeUnitId) {
        Map<String, GraphNode> nodeByUnitId = new LinkedHashMap<>();
        for (CodeUnitInfo unit : result.codeUnits()) {
            String role = roleByCodeUnitId == null ? null : roleByCodeUnitId.get(unit.id());
            nodeByUnitId.put(unit.id(), new GraphNode(
                    unit.id(),
                    unit.name(),
                    qualifiedNameOf(unit),
                    unit.kind(),
                    role == null ? "" : role,
                    unit.filePath(),
                    unit.startLine(),
                    unit.endLine()));
        }

        List<DependencyEdge> validEdges = new ArrayList<>();
        for (DependencyEdge edge : result.dependencies()) {
            if (!nodeByUnitId.containsKey(edge.fromCodeUnitId())
                    || !nodeByUnitId.containsKey(edge.toCodeUnitId())) {
                log.warn("丢弃端点不在单元集合里的边：{} -> {}", edge.fromCodeUnitId(), edge.toCodeUnitId());
                continue;
            }
            validEdges.add(edge);
        }

        Set<String> connected = new HashSet<>();
        validEdges.forEach(edge -> {
            connected.add(edge.fromCodeUnitId());
            connected.add(edge.toCodeUnitId());
        });

        List<GraphNode> nodes = nodeByUnitId.entrySet().stream()
                .filter(entry -> connected.contains(entry.getKey()))
                .sorted(Map.Entry.comparingByKey())
                .map(Map.Entry::getValue)
                .toList();

        List<String> isolatedIds = nodeByUnitId.keySet().stream()
                .filter(id -> !connected.contains(id))
                .sorted()
                .toList();

        List<DependencyEdge> edges = validEdges.stream()
                .sorted(Comparator.comparing(DependencyEdge::id))
                .toList();

        return new DependencyGraph(result.repositoryId(), result.language(), result.framework(),
                nodes, edges, isolatedIds, renderer.render(nodes, edges));
    }

    /**
     * 以某个单元为中心取 {@code depth} 跳邻域（入边与出边都算），供「点击某个类看它的依赖图」。
     *
     * <p>孤立类不在图里，取它的邻域得到空图 —— 调用方应据此显示「没有依赖」。
     */
    public DependencyGraph neighborhoodOf(DependencyGraph graph, String codeUnitId, int depth) {
        Map<String, GraphNode> nodesById = new LinkedHashMap<>();
        graph.nodes().forEach(node -> nodesById.put(node.id(), node));
        if (!nodesById.containsKey(codeUnitId)) {
            return new DependencyGraph(graph.repositoryId(), graph.language(), graph.framework(),
                    List.of(), List.of(), List.of(), renderer.render(List.of(), List.of()));
        }

        Set<String> selected = new HashSet<>();
        selected.add(codeUnitId);
        Set<String> frontier = new HashSet<>(selected);
        for (int step = 0; step < depth; step++) {
            Set<String> next = new HashSet<>();
            for (DependencyEdge edge : graph.edges()) {
                if (frontier.contains(edge.fromCodeUnitId())
                        && !selected.contains(edge.toCodeUnitId())
                        && nodesById.containsKey(edge.toCodeUnitId())) {
                    next.add(edge.toCodeUnitId());
                }
                if (frontier.contains(edge.toCodeUnitId())
                        && !selected.contains(edge.fromCodeUnitId())
                        && nodesById.containsKey(edge.fromCodeUnitId())) {
                    next.add(edge.fromCodeUnitId());
                }
            }
            if (next.isEmpty()) {
                break;
            }
            selected.addAll(next);
            frontier = next;
        }

        List<GraphNode> nodes = selected.stream()
                .map(nodesById::get)
                .sorted(Comparator.comparing(GraphNode::id))
                .toList();
        List<DependencyEdge> edges = graph.edges().stream()
                .filter(edge -> selected.contains(edge.fromCodeUnitId())
                        && selected.contains(edge.toCodeUnitId()))
                .sorted(Comparator.comparing(DependencyEdge::id))
                .toList();

        return new DependencyGraph(graph.repositoryId(), graph.language(), graph.framework(),
                nodes, edges, List.of(), renderer.render(nodes, edges));
    }

    private static String qualifiedNameOf(CodeUnitInfo unit) {
        return unit.packageName().isEmpty()
                ? unit.name()
                : unit.packageName() + "." + unit.name();
    }
}
