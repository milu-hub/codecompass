package com.codecompass.graph;

/**
 * 依赖图里的一个节点，即一个代码单元的**展示视图**。
 *
 * <p>{@code id} 沿用 {@code CodeUnitInfo.id}，作为跨接口的稳定标识；{@code label} 是给
 * 人看的名字。二者分开是因为 id 里含路径等展示上难看的字符。
 *
 * <p>{@code role} 来自调用方传入的映射（如 T5 的分类器产出），本包不认识任何框架概念。
 */
public record GraphNode(
        String id,
        String label,
        String qualifiedName,
        String kind,
        String role,
        String filePath,
        int startLine,
        int endLine) {
}
