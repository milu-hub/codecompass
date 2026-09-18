package com.codecompass.persistence;

import java.time.Instant;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/** progress 表（SCHEMA_F2_F6.md）的 JPA 实体。 */
@Entity
@Table(name = "progress")
public class ProgressEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "client_id", nullable = false, length = 64)
    private String clientId;

    @Column(name = "repo_url", nullable = false, length = 500)
    private String repoUrl;

    @Column(name = "code_unit_id", nullable = false, length = 128)
    private String codeUnitId;

    @Column(name = "status", nullable = false, length = 16)
    private String status;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected ProgressEntity() {
    }

    public ProgressEntity(String clientId, String repoUrl, String codeUnitId, String status, Instant updatedAt) {
        this.clientId = clientId;
        this.repoUrl = repoUrl;
        this.codeUnitId = codeUnitId;
        this.status = status;
        this.updatedAt = updatedAt;
    }

    public Long getId() {
        return id;
    }

    public String getClientId() {
        return clientId;
    }

    public String getRepoUrl() {
        return repoUrl;
    }

    public String getCodeUnitId() {
        return codeUnitId;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    public void setUpdatedAt(Instant updatedAt) {
        this.updatedAt = updatedAt;
    }
}
