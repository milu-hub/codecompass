package com.codecompass.service;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import com.codecompass.persistence.LearningPathEntity;
import com.codecompass.persistence.LearningPathRepository;
import com.codecompass.persistence.ProgressEntity;
import com.codecompass.persistence.ProgressRepository;
import com.codecompass.testutil.MutableClock;

import tools.jackson.databind.json.JsonMapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * F5 进度：状态校验、upsert、PATH_FINISHED（全部路线步骤 done 才解锁）。
 */
class ProgressServiceTest {

    private ProgressRepository progressRepository;
    private LearningPathRepository learningPathRepository;
    private AchievementService achievementService;
    private ProgressService service;

    @BeforeEach
    void setUp() {
        progressRepository = Mockito.mock(ProgressRepository.class);
        learningPathRepository = Mockito.mock(LearningPathRepository.class);
        achievementService = Mockito.mock(AchievementService.class);
        service = new ProgressService(progressRepository, learningPathRepository,
                achievementService, JsonMapper.builder().build(),
                new MutableClock(Instant.parse("2026-09-18T12:00:00Z")));
    }

    @Test
    @DisplayName("非法状态被拒绝")
    void invalidStatusRejected() {
        assertThatThrownBy(() -> service.update("c1", "r", "u1", "finished"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("unread / reading / done");
    }

    @Test
    @DisplayName("upsert 更新已存在的进度")
    void updateExisting() {
        ProgressEntity existing = new ProgressEntity("c1", "r", "u1", "reading",
                Instant.parse("2026-09-18T10:00:00Z"));
        when(progressRepository.findByClientIdAndRepoUrlAndCodeUnitId("c1", "r", "u1"))
                .thenReturn(Optional.of(existing));

        Progress progress = service.update("c1", "r", "u1", "done");

        assertThat(progress.status()).isEqualTo("done");
        verify(progressRepository).save(existing);
    }

    @Test
    @DisplayName("PATH_FINISHED：全部步骤 done 时触发 path_done")
    void pathFinishedUnlocksWhenAllStepsDone() {
        when(progressRepository.findByClientIdAndRepoUrlAndCodeUnitId(anyString(), anyString(), anyString()))
                .thenReturn(Optional.of(new ProgressEntity("c1", "r", "u", "done",
                        Instant.parse("2026-09-18T10:00:00Z"))));
        LearningPathEntity path = new LearningPathEntity("r", "sha",
                "{\"repoUrl\":\"r\",\"commitSha\":\"sha\",\"steps\":[{\"order\":1,\"codeUnitId\":\"u\",\"reason\":\"x\",\"estimatedMinutes\":5}]}",
                Instant.parse("2026-09-18T10:00:00Z"));
        when(learningPathRepository.findFirstByRepoUrlOrderByCreatedAtDesc("r"))
                .thenReturn(Optional.of(path));

        service.update("c1", "r", "u", "done");

        verify(achievementService).record("c1", "path_done", "r", "r");
    }

    @Test
    @DisplayName("步骤未全 done：不触发 path_done")
    void pathNotFinishedWhenStepsPending() {
        when(progressRepository.findByClientIdAndRepoUrlAndCodeUnitId("c1", "r", "u2"))
                .thenReturn(Optional.empty());
        when(progressRepository.findByClientIdAndRepoUrlAndCodeUnitId("c1", "r", "u1"))
                .thenReturn(Optional.of(new ProgressEntity("c1", "r", "u1", "done",
                        Instant.parse("2026-09-18T10:00:00Z"))));
        LearningPathEntity path = new LearningPathEntity("r", "sha",
                "{\"repoUrl\":\"r\",\"commitSha\":\"sha\",\"steps\":["
                        + "{\"order\":1,\"codeUnitId\":\"u1\",\"reason\":\"x\",\"estimatedMinutes\":5},"
                        + "{\"order\":2,\"codeUnitId\":\"u2\",\"reason\":\"x\",\"estimatedMinutes\":5}]}",
                Instant.parse("2026-09-18T10:00:00Z"));
        when(learningPathRepository.findFirstByRepoUrlOrderByCreatedAtDesc("r"))
                .thenReturn(Optional.of(path));

        service.update("c1", "r", "u1", "done");

        verify(achievementService, never()).record(anyString(), anyString(), anyString(), anyString());
    }
}
