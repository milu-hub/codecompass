package com.codecompass.persistence;

import java.time.Instant;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/** user_actions 表（SCHEMA_F2_F6.md）的 JPA 实体。 */
@Entity
@Table(name = "user_actions")
public class UserActionEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "client_id", nullable = false, length = 64)
    private String clientId;

    @Column(name = "action", nullable = false, length = 32)
    private String action;

    @Column(name = "repo_url", length = 500)
    private String repoUrl;

    @Column(name = "ref_id", length = 128)
    private String refId;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    protected UserActionEntity() {
    }

    public UserActionEntity(String clientId, String action, String repoUrl, String refId, Instant createdAt) {
        this.clientId = clientId;
        this.action = action;
        this.repoUrl = repoUrl;
        this.refId = refId;
        this.createdAt = createdAt;
    }

    public Long getId() {
        return id;
    }

    public String getClientId() {
        return clientId;
    }

    public String getAction() {
        return action;
    }

    public String getRepoUrl() {
        return repoUrl;
    }

    public String getRefId() {
        return refId;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
