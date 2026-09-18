package com.codecompass.persistence;

import java.time.Instant;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/** qa_history 表（T21 追加）的 JPA 实体。 */
@Entity
@Table(name = "qa_history")
public class QaHistoryEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "client_id", nullable = false, length = 64)
    private String clientId;

    @Column(name = "repo_url", nullable = false, length = 500)
    private String repoUrl;

    @Column(name = "commit_sha", nullable = false, length = 64)
    private String commitSha;

    @Column(name = "question", nullable = false, columnDefinition = "text")
    private String question;

    @Column(name = "answer_json", nullable = false, columnDefinition = "text")
    private String answerJson;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    protected QaHistoryEntity() {
    }

    public QaHistoryEntity(String clientId, String repoUrl, String commitSha, String question,
                           String answerJson, Instant createdAt) {
        this.clientId = clientId;
        this.repoUrl = repoUrl;
        this.commitSha = commitSha;
        this.question = question;
        this.answerJson = answerJson;
        this.createdAt = createdAt;
    }

    public String getRepoUrl() {
        return repoUrl;
    }

    public String getQuestion() {
        return question;
    }

    public String getAnswerJson() {
        return answerJson;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
