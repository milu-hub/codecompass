package com.codecompass.graph;

import java.util.List;

import com.codecompass.analyzer.DependencyEdge;

/**
 * 语言中立的类级依赖图。
 *
 * <p><b>默认不含孤立节点</b>：实测微服务样本有 41% 的单元没有任何边，全画出来会散落一堆
 * 孤立方框。孤立单元不丢 —— 完整列在 {@link #isolatedCodeUnitIds()} 里供前端提示。
 *
 * <p><b>引用完整性</b>：每条边的两端必须都在 {@link #nodes()} 里。Mermaid 遇到未声明的
 * 节点会静默创建无标签幽灵节点，所以这条不变量必须在构建阶段保证，而不是寄望渲染器。
 *
 * <p><b>{@code mermaid} 是派生字段</b>：结构是契约、文本是便利输出。换渲染器只需换一个
 * {@link MermaidRenderer}，图模型不动。
 */
public record DependencyGraph(
        String repositoryId,
        String language,
        String framework,
        List<GraphNode> nodes,
        List<DependencyEdge> edges,
        List<String> isolatedCodeUnitIds,
        String mermaid) {

    public DependencyGraph {
        nodes = nodes == null ? List.of() : List.copyOf(nodes);
        edges = edges == null ? List.of() : List.copyOf(edges);
        isolatedCodeUnitIds = isolatedCodeUnitIds == null ? List.of() : List.copyOf(isolatedCodeUnitIds);
    }
}
