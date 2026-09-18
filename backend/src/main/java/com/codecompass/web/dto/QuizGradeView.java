package com.codecompass.web.dto;

/** 判分结果。 */
public record QuizGradeView(int correct, int total, double accuracy) {
}
