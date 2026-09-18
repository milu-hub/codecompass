package com.codecompass.persistence;

import java.time.Instant;
import java.util.UUID;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.transaction.annotation.Transactional;

import tools.jackson.databind.json.JsonMapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * learning_paths 的 JSON 列（@JdbcTypeCode(SqlTypes.JSON)）读写回环 +
 * (repo_url, commit_sha) 唯一约束。
 *
 * <p>两条纪律（跑在**真 MySQL** 上时才会暴露，H2 每次新建库所以掩盖）：
 * <ol>
 *   <li>回环断言用**解析后的 JSON 树相等**：MySQL 会规范化 JSON 格式（去空格/排键），字节级比较只在 H2 成立；</li>
 *   <li>@Transactional 自动回滚 + 每轮唯一键：否则上一轮留下的行会让唯一约束插入直接失败。</li>
 * </ol>
 */
@SpringBootTest
@Transactional
class LearningPathRepositoryTest {

    private static final JsonMapper MAPPER = JsonMapper.builder().build();

    @Autowired
    private LearningPathRepository repository;

    private static LearningPathEntity entity(String sha) {
        return new LearningPathEntity("https://github.com/a/b", sha,
                "{\"repoUrl\":\"https://github.com/a/b\",\"commitSha\":\"" + sha
                        + "\",\"steps\":[{\"order\":1,\"codeUnitId\":\"u1\",\"reason\":\"先读入口\",\"estimatedMinutes\":5}]}",
                Instant.parse("2026-09-18T12:00:00Z"));
    }

    @Test
    @DisplayName("pathJson 的 JSON 列写读回环：语义一致且关键字段可取")
    void jsonRoundTrip() {
        String sha = "sha-" + UUID.randomUUID();
        LearningPathEntity saved = repository.saveAndFlush(entity(sha));

        LearningPathEntity loaded = repository.findByRepoUrlAndCommitSha(
                "https://github.com/a/b", sha).orElseThrow();

        assertThat(MAPPER.readTree(loaded.getPathJson()))
                .as("JSON 内容语义一致（MySQL 会规范化格式，故不比字节）")
                .isEqualTo(MAPPER.readTree(saved.getPathJson()));
        assertThat(MAPPER.readTree(loaded.getPathJson()).path("steps").get(0).path("codeUnitId").asText())
                .isEqualTo("u1");
        assertThat(loaded.getCreatedAt()).isNotNull();
    }

    @Test
    @DisplayName("同 (repoUrl, commitSha) 二次插入触发唯一约束")
    void uniqueConstraintRejectsDuplicate() {
        String sha = "sha-uniq-" + UUID.randomUUID();
        repository.saveAndFlush(entity(sha));

        assertThatThrownBy(() -> repository.saveAndFlush(entity(sha)))
                .isInstanceOf(DataIntegrityViolationException.class);
    }
}
