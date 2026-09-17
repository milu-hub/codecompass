package com.codecompass.analyzer;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * 按语言派发到对应分析器。
 *
 * <p><b>它是「业务层不点名实现类」的结构保证</b>。没有它，T7 只能直接注入
 * {@code JavaSpringAnalyzer}，TASKBOOK §07 的「新增语言只应新增一个实现与一份配置」
 * 与 T12 的「加一个 Python stub 实现，业务层代码不需要修改」就都落不了地。
 *
 * <p>本类内部**没有任何语言分支**，只做一次 Map 查表 —— 这是它能对任意新语言
 * （包括尚未存在的语言）一视同仁的原因。
 */
public class LanguageAnalyzerRegistry {

    private final Map<String, LanguageAnalyzer> analyzersByLanguage;

    public LanguageAnalyzerRegistry(List<LanguageAnalyzer> analyzers) {
        Map<String, LanguageAnalyzer> index = new LinkedHashMap<>();
        if (analyzers != null) {
            for (LanguageAnalyzer analyzer : analyzers) {
                String language = analyzer.language();
                if (language == null || language.isBlank()) {
                    throw new IllegalArgumentException(
                            "分析器 " + analyzer.getClass().getName() + " 的 language() 不能为空");
                }
                LanguageAnalyzer previous = index.putIfAbsent(language, analyzer);
                if (previous != null) {
                    throw new IllegalArgumentException("语言 " + language + " 被多个分析器声明："
                            + previous.getClass().getName() + " 与 " + analyzer.getClass().getName()
                            + "。同一语言只能有一个 LanguageAnalyzer 实现。");
                }
            }
        }
        this.analyzersByLanguage = Map.copyOf(index);
    }

    /** 未支持的语言返回空 —— 由调用方给出明确错误，而不是这里悄悄返回空结果。 */
    public Optional<LanguageAnalyzer> forLanguage(String language) {
        if (language == null || language.isBlank()) {
            return Optional.empty();
        }
        return Optional.ofNullable(analyzersByLanguage.get(language));
    }

    /** 供诊断与错误信息使用，例如「不支持的语言：cobol，已支持 [java]」。 */
    public Set<String> supportedLanguages() {
        return analyzersByLanguage.keySet();
    }
}
