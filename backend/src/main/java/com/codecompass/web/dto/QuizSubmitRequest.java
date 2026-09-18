package com.codecompass.web.dto;

import java.util.List;

/** 测验提交：每题给出 0-based 选项下标。 */
public record QuizSubmitRequest(List<Answer> answers) {

    public record Answer(String questionId, int answerIndex) {
    }
}
