package com.codecompass.service;

import java.time.Instant;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import com.codecompass.persistence.AnonymousUserEntity;
import com.codecompass.persistence.AnonymousUserRepository;
import com.codecompass.testutil.MutableClock;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class IdentityServiceTest {

    private AnonymousUserRepository repository;
    private IdentityService service;

    @BeforeEach
    void setUp() {
        repository = Mockito.mock(AnonymousUserRepository.class);
        service = new IdentityService(repository,
                new MutableClock(Instant.parse("2026-09-18T12:00:00Z")));
    }

    @Test
    @DisplayName("无 cookie：生成 UUID 并建档")
    void resolveWithoutCookieCreatesUuid() {
        when(repository.findById(any())).thenReturn(Optional.empty());

        String clientId = service.resolve(null);

        assertThat(clientId).isNotBlank();
        verify(repository).save(any());
    }

    @Test
    @DisplayName("有 cookie：沿用并刷新 last_seen")
    void resolveWithCookieReusesId() {
        AnonymousUserEntity entity = new AnonymousUserEntity("client-1", null,
                Instant.parse("2026-09-18T10:00:00Z"), Instant.parse("2026-09-18T10:00:00Z"));
        when(repository.findById("client-1")).thenReturn(Optional.of(entity));

        assertThat(service.resolve("client-1")).isEqualTo("client-1");
        verify(repository).save(entity);
    }

    @Test
    @DisplayName("updateNickname 存昵称")
    void updateNickname() {
        AnonymousUserEntity entity = new AnonymousUserEntity("client-1", null,
                Instant.parse("2026-09-18T10:00:00Z"), Instant.parse("2026-09-18T10:00:00Z"));
        when(repository.findById("client-1")).thenReturn(Optional.of(entity));

        AnonymousUser user = service.updateNickname("client-1", " 小明 ");

        assertThat(user.nickname()).isEqualTo("小明");
    }
}
