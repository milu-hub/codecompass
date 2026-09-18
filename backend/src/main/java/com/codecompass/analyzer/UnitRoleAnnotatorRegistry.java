package com.codecompass.analyzer;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 按语言派发到对应的角色标注器（P5 起，与 {@link LanguageAnalyzerRegistry} 同构）。
 *
 * <p>此前编排层注入的是唯一一个 {@link UnitRoleAnnotator} Bean（Spring 专用实现），
 * 加第二门语言就不得不改业务层 —— 这正好把 T12 承诺的「加语言只加一个实现与一份配置」
 * 作废。本类内部零语言分支，只做一次 Map 查表。
 */
public class UnitRoleAnnotatorRegistry {

    private final Map<String, UnitRoleAnnotator> annotatorsByLanguage;

    public UnitRoleAnnotatorRegistry(List<UnitRoleAnnotator> annotators) {
        Map<String, UnitRoleAnnotator> index = new LinkedHashMap<>();
        if (annotators != null) {
            for (UnitRoleAnnotator annotator : annotators) {
                String language = annotator.language();
                if (language == null || language.isBlank()) {
                    throw new IllegalArgumentException(
                            "标注器 " + annotator.getClass().getName() + " 的 language() 不能为空");
                }
                UnitRoleAnnotator previous = index.putIfAbsent(language, annotator);
                if (previous != null) {
                    throw new IllegalArgumentException("语言 " + language + " 被多个标注器声明："
                            + previous.getClass().getName() + " 与 " + annotator.getClass().getName());
                }
            }
        }
        this.annotatorsByLanguage = Map.copyOf(index);
    }

    /** 未支持的语言返回空 —— 由调用方当作"无角色"处理，而不是悄悄出错。 */
    public Optional<UnitRoleAnnotator> forLanguage(String language) {
        if (language == null || language.isBlank()) {
            return Optional.empty();
        }
        return Optional.ofNullable(annotatorsByLanguage.get(language));
    }
}
