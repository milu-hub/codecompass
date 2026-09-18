package com.codecompass.persistence;

import java.time.Instant;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** notes 的 CRUD 与唯一约束（H2）。 */
@SpringBootTest
class NoteRepositoryTest {

    @Autowired
    private NoteRepository repository;

    @Test
    @DisplayName("按 (clientId, repoUrl, codeUnitId) 唯一，重复插入触发约束")
    void uniqueConstraint() {
        Instant now = Instant.parse("2026-09-18T12:00:00Z");
        repository.saveAndFlush(new NoteEntity("c1-uniq", "r", "u1", "内容", now, now));

        assertThatThrownBy(() -> repository.saveAndFlush(
                new NoteEntity("c1-uniq", "r", "u1", "别的", now, now)))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    @DisplayName("按 clientId+repoUrl 查询，隔离不同用户")
    void queryIsScopedToClient() {
        Instant now = Instant.parse("2026-09-18T12:00:00Z");
        repository.saveAndFlush(new NoteEntity("c1-q", "r", "u1", "甲的", now, now));
        repository.saveAndFlush(new NoteEntity("c2-q", "r", "u1", "乙的", now, now));

        assertThat(repository.findByClientIdAndRepoUrl("c1-q", "r")).hasSize(1);
        assertThat(repository.findByClientIdAndRepoUrl("c2-q", "r").get(0).getContent()).isEqualTo("乙的");
    }
}
