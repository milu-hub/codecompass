package com.codecompass.persistence;

import java.time.Instant;
import java.util.UUID;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.transaction.annotation.Transactional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * notes 的 CRUD 与唯一约束。@Transactional + 每轮唯一 clientId：
 * 跑在真 MySQL 上时，固定键会被上一轮留下的行挡住（H2 每次新建库所以掩盖）。
 */
@SpringBootTest
@Transactional
class NoteRepositoryTest {

    @Autowired
    private NoteRepository repository;

    @Test
    @DisplayName("按 (clientId, repoUrl, codeUnitId) 唯一，重复插入触发约束")
    void uniqueConstraint() {
        Instant now = Instant.parse("2026-09-18T12:00:00Z");
        String clientId = "c-uniq-" + UUID.randomUUID();
        repository.saveAndFlush(new NoteEntity(clientId, "r", "u1", "内容", now, now));

        assertThatThrownBy(() -> repository.saveAndFlush(
                new NoteEntity(clientId, "r", "u1", "别的", now, now)))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    @DisplayName("按 clientId+repoUrl 查询，隔离不同用户")
    void queryIsScopedToClient() {
        Instant now = Instant.parse("2026-09-18T12:00:00Z");
        String clientA = "c-a-" + UUID.randomUUID();
        String clientB = "c-b-" + UUID.randomUUID();
        repository.saveAndFlush(new NoteEntity(clientA, "r", "u1", "甲的", now, now));
        repository.saveAndFlush(new NoteEntity(clientB, "r", "u1", "乙的", now, now));

        assertThat(repository.findByClientIdAndRepoUrl(clientA, "r")).hasSize(1);
        assertThat(repository.findByClientIdAndRepoUrl(clientB, "r").get(0).getContent()).isEqualTo("乙的");
    }
}
