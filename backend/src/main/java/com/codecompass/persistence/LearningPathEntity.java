package com.codecompass.persistence;

import java.time.Instant;

import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/**
 * learning_paths 表（SCHEMA_F2_F6.md）的 JPA 实体。
 *
 * <p>{@code pathJson} 存 {@link com.codecompass.service.LearningPath} 的 JSON 文本，
 * 用 {@code @JdbcTypeCode(SqlTypes.JSON)} 让 Hibernate 7 在 H2 与 MySQL 上一致地
 * 读写 JSON 列（规避 T13 实测的 H2 JSON 列 JDBC 读取坑）。
 */
@Entity
@Table(name = "learning_paths")
public class LearningPathEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "repo_url", nullable = false, length = 500)
    private String repoUrl;

    @Column(name = "commit_sha", nullable = false, length = 64)
    private String commitSha;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "path_json", nullable = false, columnDefinition = "json")
    private String pathJson;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    protected LearningPathEntity() {
    }

    public LearningPathEntity(String repoUrl, String commitSha, String pathJson, Instant createdAt) {
        this.repoUrl = repoUrl;
        this.commitSha = commitSha;
        this.pathJson = pathJson;
        this.createdAt = createdAt;
    }

    public Long getId() {
        return id;
    }

    public String getRepoUrl() {
        return repoUrl;
    }

    public String getCommitSha() {
        return commitSha;
    }

    public String getPathJson() {
        return pathJson;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
