package com.codecompass.persistence;

import java.time.Instant;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * learning_paths 的 JSON 列（@JdbcTypeCode(SqlTypes.JSON)）在 H2 上的读写回环 +
 * (repo_url, commit_sha) 唯一约束。Flyway 已在上下文中建表。
 */
@SpringBootTest
class LearningPathRepositoryTest {

    @Autowired
    private LearningPathRepository repository;

    private static LearningPathEntity entity(String sha) {
        return new LearningPathEntity("https://github.com/a/b", sha,
                "{\"repoUrl\":\"https://github.com/a/b\",\"commitSha\":\"" + sha
                        + "\",\"steps\":[{\"order\":1,\"codeUnitId\":\"u1\",\"reason\":\"先读入口\",\"estimatedMinutes\":5}]}",
                Instant.parse("2026-09-18T12:00:00Z"));
    }

    @Test
    @DisplayName("pathJson 的 JSON 列在 H2 上写读回环，内容逐字一致")
    void jsonRoundTripOnH2() {
        LearningPathEntity saved = repository.saveAndFlush(entity("sha-roundtrip"));

        LearningPathEntity loaded = repository.findByRepoUrlAndCommitSha(
                "https://github.com/a/b", "sha-roundtrip").orElseThrow();

        assertThat(loaded.getPathJson()).isEqualTo(saved.getPathJson());
        assertThat(loaded.getPathJson()).contains("\"codeUnitId\":\"u1\"");
        assertThat(loaded.getCreatedAt()).isNotNull();
    }

    @Test
    @DisplayName("同 (repoUrl, commitSha) 二次插入触发唯一约束")
    void uniqueConstraintRejectsDuplicate() {
        repository.saveAndFlush(entity("sha-unique"));

        assertThatThrownBy(() -> repository.saveAndFlush(entity("sha-unique")))
                .isInstanceOf(DataIntegrityViolationException.class);
    }
}
