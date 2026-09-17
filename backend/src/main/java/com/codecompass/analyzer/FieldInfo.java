package com.codecompass.analyzer;

import java.util.List;

/**
 * 代码单元里的一个字段，对应 SCHEMA.md 中 {@code CodeUnitInfo.fields} 的元素。
 *
 * @param annotations 永不为 null
 */
public record FieldInfo(String name, String type, List<String> annotations) {

    public FieldInfo {
        annotations = ModelSupport.immutableCopy(annotations);
    }
}
