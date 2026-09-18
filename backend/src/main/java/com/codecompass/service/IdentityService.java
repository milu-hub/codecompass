package com.codecompass.service;

import java.time.Clock;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import com.codecompass.persistence.AnonymousUserEntity;
import com.codecompass.persistence.AnonymousUserRepository;

/**
 * F5 匿名身份服务：解析/创建 clientId 并刷新 last_seen。
 */
public class IdentityService {

    private final AnonymousUserRepository repository;
    private final Clock clock;

    public IdentityService(AnonymousUserRepository repository, Clock clock) {
        this.repository = repository;
        this.clock = clock;
    }

    /** 有 cookie 用之，无则生成 UUID；随后 upsert last_seen。 */
    public String resolve(String existingClientId) {
        String clientId = existingClientId == null || existingClientId.isBlank()
                ? UUID.randomUUID().toString() : existingClientId.trim();
        Instant now = clock.instant();
        AnonymousUserEntity entity = repository.findById(clientId).orElse(null);
        if (entity == null) {
            repository.save(new AnonymousUserEntity(clientId, null, now, now));
        } else {
            entity.setLastSeenAt(now);
            repository.save(entity);
        }
        return clientId;
    }

    public Optional<AnonymousUser> find(String clientId) {
        if (clientId == null) {
            return Optional.empty();
        }
        return repository.findById(clientId)
                .map(entity -> new AnonymousUser(entity.getClientId(), entity.getNickname(),
                        entity.getCreatedAt()));
    }

    public AnonymousUser updateNickname(String clientId, String nickname) {
        AnonymousUserEntity entity = repository.findById(clientId)
                .orElseThrow(() -> new IllegalArgumentException("身份不存在"));
        entity.setNickname(nickname == null || nickname.isBlank() ? null : nickname.trim());
        repository.save(entity);
        return new AnonymousUser(entity.getClientId(), entity.getNickname(), entity.getCreatedAt());
    }
}
