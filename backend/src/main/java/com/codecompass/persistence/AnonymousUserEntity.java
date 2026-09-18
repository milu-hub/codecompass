package com.codecompass.persistence;

import java.time.Instant;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/** anonymous_users 表（SCHEMA_F2_F6.md）的 JPA 实体。 */
@Entity
@Table(name = "anonymous_users")
public class AnonymousUserEntity {

    @Id
    @Column(name = "client_id", nullable = false, length = 64)
    private String clientId;

    @Column(name = "nickname", length = 64)
    private String nickname;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "last_seen_at", nullable = false)
    private Instant lastSeenAt;

    protected AnonymousUserEntity() {
    }

    public AnonymousUserEntity(String clientId, String nickname, Instant createdAt, Instant lastSeenAt) {
        this.clientId = clientId;
        this.nickname = nickname;
        this.createdAt = createdAt;
        this.lastSeenAt = lastSeenAt;
    }

    public String getClientId() {
        return clientId;
    }

    public String getNickname() {
        return nickname;
    }

    public void setNickname(String nickname) {
        this.nickname = nickname;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public void setLastSeenAt(Instant lastSeenAt) {
        this.lastSeenAt = lastSeenAt;
    }

    public Instant getLastSeenAt() {
        return lastSeenAt;
    }
}
