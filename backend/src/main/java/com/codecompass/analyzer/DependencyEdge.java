package com.codecompass.analyzer;

/**
 * 两个代码单元之间的一条依赖边，对应 SCHEMA.md 的 {@code DependencyEdge}。
 *
 * <p><b>只表达仓库内的边</b>：{@code toCodeUnitId} 必须能在同一次结果的 codeUnits 中找到。
 * 仓库外依赖（如 Spring 的 {@code @Controller}）不产生边 —— 注解识别体现在
 * {@link CodeUnitInfo#annotations()} 里。代价是 {@code kind="annotation"} 的边只对
 * 仓内自定义注解有意义。
 *
 * @param kind 开放取值。Java 约定为 import / field / annotation。
 *             刻意不用 enum，理由同 {@link CodeUnitInfo#kind()}。
 */
public record DependencyEdge(
        String id,
        String repositoryId,
        String fromCodeUnitId,
        String toCodeUnitId,
        String kind,
        String language) {
}
