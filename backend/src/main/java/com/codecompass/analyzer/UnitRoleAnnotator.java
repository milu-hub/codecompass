package com.codecompass.analyzer;

import java.util.Map;

/**
 * 给分析结果里的单元补充框架级标签（如 T5 的"角色"）。
 *
 * <p><b>为什么需要这个 seam</b>：编排层（T7）需要角色映射，而产出的实现是
 * {@code analyzer/java/} 里的分类器（Spring 概念）。若编排层直接 import 它，
 * 业务层就出现了语言特有依赖，违反「新增语言只应新增一个 LanguageAnalyzer 实现与
 * 一份配置」。本接口是语言中立的 —— Map 进 Map 出 —— 编排层只依赖它。
 *
 * <p><b>P5 起按语言查表</b>：{@link #language()} 让编排层通过
 * {@link UnitRoleAnnotatorRegistry} 按语言选中标注器，同一语言只能有一个实现。
 * 无此概念的语言（或框架未识别时）返回空 Map。
 */
public interface UnitRoleAnnotator {

    /** 负责的语言标签，取值与 {@code scan.sources[].language} 对应。 */
    String language();

    /** 单元 id → 标签；仅返回能标注上的单元。 */
    Map<String, String> annotate(AnalyzeResult result);
}
