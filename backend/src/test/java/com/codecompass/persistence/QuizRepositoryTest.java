package com.codecompass.persistence;

import java.time.Instant;
import java.util.UUID;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;

import tools.jackson.databind.json.JsonMapper;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * quizzes 的 JSON 列写读回环。断言用解析后的 JSON 树相等（MySQL 会规范化 JSON 格式，
 * 字节级比较只在 H2 成立）；@Transactional + 唯一 id 保证不污染真 MySQL。
 */
@SpringBootTest
@Transactional
class QuizRepositoryTest {

    private static final JsonMapper MAPPER = JsonMapper.builder().build();

    @Autowired
    private QuizRepository repository;

    @Test
    @DisplayName("quizJson 写读回环：语义一致且关键字段可取")
    void quizJsonRoundTrip() {
        String id = "quiz-" + UUID.randomUUID();
        String json = "{\"id\":\"q1\",\"questions\":[{\"type\":\"true_false\",\"answer\":0}]}";
        QuizEntity saved = repository.save(new QuizEntity(
                id, "https://github.com/a/b", "sha", json,
                Instant.parse("2026-09-18T12:00:00Z")));

        QuizEntity loaded = repository.findById(id).orElseThrow();

        assertThat(MAPPER.readTree(loaded.getQuizJson()))
                .isEqualTo(MAPPER.readTree(saved.getQuizJson()));
        assertThat(MAPPER.readTree(loaded.getQuizJson()).path("questions").get(0).path("answer").asInt())
                .isZero();
    }
}
