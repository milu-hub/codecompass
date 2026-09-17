package com.codecompass.service;

import java.util.List;

/**
 * 问答结果。references 的行号只可能来自检索层 —— LLM 报出的引用逐条比对后
 * 才能进这个对象（不匹配的在 {@link AnswerService} 里被丢弃并重试）。
 *
 * <p>{@code Reference} 行号沿用全项目口径：1-based 闭区间，构造器守卫。
 */
public record AnswerResponse(String answer, List<Reference> references, String model) {

    public AnswerResponse {
        references = references == null ? List.of() : List.copyOf(references);
    }

    public record Reference(String file, String language, int startLine, int endLine) {

        public Reference {
            if (file == null || file.isBlank()) {
                throw new IllegalArgumentException("file 不能为空");
            }
            if (language == null || language.isBlank()) {
                throw new IllegalArgumentException("language 不能为空");
            }
            if (startLine < 1) {
                throw new IllegalArgumentException("startLine 必须 ≥ 1：" + startLine);
            }
            if (endLine < startLine) {
                throw new IllegalArgumentException("endLine 必须 ≥ startLine");
            }
        }
    }
}
