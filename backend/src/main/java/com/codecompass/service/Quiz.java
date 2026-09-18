package com.codecompass.service;

import java.util.List;

/**
 * F4 自动测验：一次生成的一套题。
 *
 * <p>{@code Question.answer} 是正确选项的 **0-based 下标**（SCHEMA 示例的 {@code "A"}
 * 与选项文本有歧义，统一用下标判分）。reference 沿用 {@link AnswerResponse.Reference}
 * 的 1-based 闭区间守卫 —— 行号必须来自发给 LLM 的源码（内存快照），不允许编造。
 */
public record Quiz(String id, String repoUrl, String commitSha, List<Question> questions) {

    public Quiz {
        questions = questions == null ? List.of() : List.copyOf(questions);
    }

    public record Question(
            String id,
            String type,
            String question,
            List<String> options,
            int answer,
            String explanation,
            AnswerResponse.Reference reference) {

        public Question {
            options = options == null ? List.of() : List.copyOf(options);
        }
    }
}
