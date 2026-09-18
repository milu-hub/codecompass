package com.codecompass.persistence;

import java.time.Instant;

import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/** share_snapshots 表（SCHEMA_F2_F6.md）的 JPA 实体。 */
@Entity
@Table(name = "share_snapshots")
public class ShareSnapshotEntity {

    @Id
    @Column(name = "id", nullable = false, length = 32)
    private String id;

    @Column(name = "repo_url", nullable = false, length = 500)
    private String repoUrl;

    @Column(name = "commit_sha", nullable = false, length = 64)
    private String commitSha;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "snapshot_json", nullable = false, columnDefinition = "json")
    private String snapshotJson;

    @Column(name = "expires_at", nullable = false)
    private Instant expiresAt;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    protected ShareSnapshotEntity() {
    }

    public ShareSnapshotEntity(String id, String repoUrl, String commitSha, String snapshotJson,
                               Instant expiresAt, Instant createdAt) {
        this.id = id;
        this.repoUrl = repoUrl;
        this.commitSha = commitSha;
        this.snapshotJson = snapshotJson;
        this.expiresAt = expiresAt;
        this.createdAt = createdAt;
    }

    public String getId() {
        return id;
    }

    public String getRepoUrl() {
        return repoUrl;
    }

    public String getCommitSha() {
        return commitSha;
    }

    public String getSnapshotJson() {
        return snapshotJson;
    }

    public Instant getExpiresAt() {
        return expiresAt;
    }
}
