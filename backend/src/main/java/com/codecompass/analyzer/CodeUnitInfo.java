package com.codecompass.analyzer;

import java.util.List;

/**
 * 一个代码单元（类 / 接口 / 枚举 / 模块 / trait …），对应 SCHEMA.md 的 {@code CodeUnitInfo}。
 *
 * <p><b>命名语言中立</b>：刻意不叫 ClassInfo / JavaClass —— 那样会把某门语言的概念钉进核心模型。
 *
 * <p><b>零 Jackson 注解</b>：核心模型不依赖任何序列化框架，序列化由 web 层负责。
 *
 * <p><b>id 必须确定性</b>：同一输入必须产出同一组 id（不得用 UUID），否则 T11 的缓存 key
 * 不稳定、测试无法断言可复现。具体格式由各语言分析器自定，对业务层不透明。
 *
 * @param kind         开放取值。Java 约定为 class / interface / enum / record / annotation。
 *                     刻意不用 enum —— 枚举会成为每门新语言都必须编辑的共享类型，
 *                     违反「新增语言只应新增一个实现与一份配置」。
 * @param framework    识别结果而非配置项；未识别时为 {@code ""}（纯 Java 仓库没有框架）
 * @param packageName  默认包为 {@code ""}
 * @param startLine    1-based，闭区间
 */
public record CodeUnitInfo(
        String id,
        String repositoryId,
        String filePath,
        String language,
        String framework,
        String packageName,
        String name,
        String kind,
        List<String> annotations,
        List<FieldInfo> fields,
        int startLine,
        int endLine) {

    public CodeUnitInfo {
        annotations = ModelSupport.immutableCopy(annotations);
        fields = ModelSupport.immutableCopy(fields);
        ModelSupport.requireValidLineRange(startLine, endLine);
    }
}
