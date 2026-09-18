package com.codecompass.persistence;

import java.time.Instant;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/** achievements 表（SCHEMA_F2_F6.md）的 JPA 实体。 */
@Entity
@Table(name = "achievements")
public class AchievementEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "client_id", nullable = false, length = 64)
    private String clientId;

    @Column(name = "code", nullable = false, length = 64)
    private String code;

    @Column(name = "unlocked_at", nullable = false)
    private Instant unlockedAt;

    protected AchievementEntity() {
    }

    public AchievementEntity(String clientId, String code, Instant unlockedAt) {
        this.clientId = clientId;
        this.code = code;
        this.unlockedAt = unlockedAt;
    }

    public String getClientId() {
        return clientId;
    }

    public String getCode() {
        return code;
    }

    public Instant getUnlockedAt() {
        return unlockedAt;
    }
}
