package com.codecompass.persistence;

import java.time.Instant;

import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/** quizzes 表（SCHEMA_F2_F6.md）的 JPA 实体。 */
@Entity
@Table(name = "quizzes")
public class QuizEntity {

    @Id
    @Column(name = "id", nullable = false, length = 64)
    private String id;

    @Column(name = "repo_url", nullable = false, length = 500)
    private String repoUrl;

    @Column(name = "commit_sha", nullable = false, length = 64)
    private String commitSha;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "quiz_json", nullable = false, columnDefinition = "json")
    private String quizJson;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    protected QuizEntity() {
    }

    public QuizEntity(String id, String repoUrl, String commitSha, String quizJson, Instant createdAt) {
        this.id = id;
        this.repoUrl = repoUrl;
        this.commitSha = commitSha;
        this.quizJson = quizJson;
        this.createdAt = createdAt;
    }

    public String getId() {
        return id;
    }

    public String getQuizJson() {
        return quizJson;
    }

    public String getRepoUrl() {
        return repoUrl;
    }

    public String getCommitSha() {
        return commitSha;
    }
}
