package com.codecompass.analyzer;

import java.util.List;

/**
 * 一个方法，对应 SCHEMA.md 的 {@code MethodInfo}。
 *
 * @param codeUnitId  指向所属的 {@link CodeUnitInfo#id()}
 * @param signature   语言原样的签名文本；核心层不解析它
 * @param annotations 永不为 null
 * @param startLine   1-based，闭区间
 */
public record MethodInfo(
        String id,
        String codeUnitId,
        String name,
        String signature,
        List<String> annotations,
        int startLine,
        int endLine) {

    public MethodInfo {
        annotations = ModelSupport.immutableCopy(annotations);
        ModelSupport.requireValidLineRange(startLine, endLine);
    }
}
