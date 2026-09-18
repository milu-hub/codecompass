package com.codecompass.persistence;

import java.time.Instant;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import static org.assertj.core.api.Assertions.assertThat;

/** quizzes 的 JSON 列在 H2 上写读回环。 */
@SpringBootTest
class QuizRepositoryTest {

    @Autowired
    private QuizRepository repository;

    @Test
    @DisplayName("quizJson 写读回环，内容逐字一致")
    void quizJsonRoundTripOnH2() {
        String json = "{\"id\":\"q1\",\"questions\":[{\"type\":\"true_false\",\"answer\":0}]}";
        QuizEntity saved = repository.save(new QuizEntity(
                "quiz-h2-1", "https://github.com/a/b", "sha", json,
                Instant.parse("2026-09-18T12:00:00Z")));

        QuizEntity loaded = repository.findById("quiz-h2-1").orElseThrow();

        assertThat(loaded.getQuizJson()).isEqualTo(json);
        assertThat(saved.getId()).isEqualTo("quiz-h2-1");
    }
}
