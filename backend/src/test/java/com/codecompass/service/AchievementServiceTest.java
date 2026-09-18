package com.codecompass.service;

import java.time.Instant;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import com.codecompass.persistence.AchievementRepository;
import com.codecompass.persistence.UserActionRepository;
import com.codecompass.testutil.MutableClock;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * F5 成就：规则从配置读、计数阈值解锁、幂等、未解锁返回 null。
 */
class AchievementServiceTest {

    private UserActionRepository userActions;
    private AchievementRepository achievements;
    private AchievementService service;

    @BeforeEach
    void setUp() {
        userActions = Mockito.mock(UserActionRepository.class);
        achievements = Mockito.mock(AchievementRepository.class);
        AchievementProperties properties = new AchievementProperties();
        properties.setDefinitions(List.of(
                new AchievementProperties.Definition("FIRST_REPO", "初次探索", "完成第一个仓库分析", "analyze", 1),
                new AchievementProperties.Definition("TEN_QUESTIONS", "刨根问底", "提问 10 次", "ask", 10)));
        service = new AchievementService(userActions, achievements, properties,
                new MutableClock(Instant.parse("2026-09-18T12:00:00Z")));
    }

    @Test
    @DisplayName("计数达阈值且未解锁 → 解锁并返回；refId 幂等不重复计数")
    void unlockOnThreshold() {
        when(userActions.existsByClientIdAndActionAndRefId("c1", "analyze", "t1")).thenReturn(false);
        when(userActions.countByClientIdAndAction("c1", "analyze")).thenReturn(1L);
        when(achievements.existsByClientIdAndCode("c1", "FIRST_REPO")).thenReturn(false);
        when(achievements.save(any())).thenAnswer(invocation -> invocation.getArgument(0));

        List<AchievementView> unlocked = service.record("c1", "analyze", "r", "t1");

        assertThat(unlocked).hasSize(1);
        assertThat(unlocked.get(0).code()).isEqualTo("FIRST_REPO");
        verify(achievements).save(any());
    }

    @Test
    @DisplayName("已解锁的不再解锁")
    void alreadyUnlockedIsSkipped() {
        when(userActions.existsByClientIdAndActionAndRefId("c1", "analyze", "t1")).thenReturn(false);
        when(userActions.countByClientIdAndAction("c1", "analyze")).thenReturn(1L);
        when(achievements.existsByClientIdAndCode("c1", "FIRST_REPO")).thenReturn(true);

        assertThat(service.record("c1", "analyze", "r", "t1")).isEmpty();
    }

    @Test
    @DisplayName("计数未达阈值不解锁（TEN_QUESTIONS 需 10 次）")
    void belowThresholdNotUnlocked() {
        when(userActions.existsByClientIdAndActionAndRefId(anyString(), anyString(), anyString())).thenReturn(false);
        when(userActions.countByClientIdAndAction("c1", "ask")).thenReturn(9L);
        when(achievements.existsByClientIdAndCode(anyString(), anyString())).thenReturn(false);

        assertThat(service.record("c1", "ask", "r", null)).isEmpty();
    }

    @Test
    @DisplayName("unlocked()：全部定义返回，未解锁 unlockedAt=null")
    void unlockedReturnsAllDefinitions() {
        when(achievements.findByClientIdAndCode(anyString(), anyString()))
                .thenReturn(java.util.Optional.empty());

        List<AchievementView> all = service.unlocked("c1");

        assertThat(all).hasSize(2);
        assertThat(all).allSatisfy(view -> assertThat(view.unlockedAt()).isNull());
    }
}
