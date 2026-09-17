package com.codecompass.retrieve;

import java.util.Locale;

/**
 * 检索到的代码片段，对应 SCHEMA.md 的 {@code RetrievedSnippet}。
 *
 * <p>行号沿用 T3 契约：1-based 闭区间。取值范围一律取自 {@code CodeUnitInfo} /
 * {@code MethodInfo}（已在构造器上守卫），因此这里不重复校验。
 *
 * <p>{@code file} 与 T2 的 {@code relativePath} 严格同口径（相对仓库根、{@code /} 分隔），
 * 因为它同时是 {@code sourceLines} 快照的 key —— 口径不一致会导致 content 静默为空。
 */
public record RetrievedSnippet(
        String file,
        String language,
        int startLine,
        int endLine,
        String content,
        double score) {
}
