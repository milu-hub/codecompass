package com.codecompass.service;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import com.codecompass.analyzer.AnalyzeResult;
import com.codecompass.analyzer.CodeUnitInfo;
import com.codecompass.persistence.LearningPathEntity;
import com.codecompass.persistence.LearningPathRepository;
import com.codecompass.persistence.NoteEntity;
import com.codecompass.persistence.NoteRepository;
import com.codecompass.persistence.QaHistoryEntity;
import com.codecompass.persistence.QaHistoryRepository;
import com.codecompass.testutil.MutableClock;

import tools.jackson.databind.json.JsonMapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * F6 快照构建：只含脱敏信息（无源码、无他人问答、无他人笔记、成就只留 code+name）。
 */
class ShareServiceTest {

    private QaHistoryRepository qaHistoryRepository;
    private LearningPathRepository learningPathRepository;
    private NoteRepository noteRepository;
    private ShareService service;

    @BeforeEach
    void setUp() {
        qaHistoryRepository = Mockito.mock(QaHistoryRepository.class);
        learningPathRepository = Mockito.mock(LearningPathRepository.class);
        noteRepository = Mockito.mock(NoteRepository.class);
        ShareProperties properties = new ShareProperties();
        properties.setExpiresDays(30);
        service = new ShareService(qaHistoryRepository, learningPathRepository, noteRepository,
                JsonMapper.builder().build(),
                new MutableClock(Instant.parse("2026-09-18T12:00:00Z")), properties);
    }

    private static AnalyzeResult result() {
        CodeUnitInfo unit = new CodeUnitInfo("repo:u", "r", "src/A.java", "java", "spring",
                "com.example", "A", "class", List.of(), List.of(), 1, 9);
        return new AnalyzeResult("r", "java", "spring", List.of(unit), List.of(), List.of(), List.of());
    }

    @Test
    @DisplayName("快照含图/路线/自己的问答/成就/笔记，无源码与 clientId")
    void snapshotContainsOnlyPublicData() {
        LearningPathEntity path = new LearningPathEntity("r", "sha",
                "{\"repoUrl\":\"r\",\"commitSha\":\"sha\",\"steps\":[{\"order\":1,\"codeUnitId\":\"repo:u\",\"reason\":\"先读入口\",\"estimatedMinutes\":5}]}",
                Instant.parse("2026-09-18T10:00:00Z"));
        when(learningPathRepository.findFirstByRepoUrlOrderByCreatedAtDesc("r"))
                .thenReturn(Optional.of(path));
        QaHistoryEntity history = new QaHistoryEntity("c1", "r", "sha", "问题？",
                "{\"answer\":\"回答\",\"references\":[],\"model\":\"deepseek-chat\"}",
                Instant.parse("2026-09-18T11:00:00Z"));
        when(qaHistoryRepository.findTop10ByClientIdAndRepoUrlOrderByCreatedAtDesc("c1", "r"))
                .thenReturn(List.of(history));
        when(noteRepository.findByClientIdAndRepoUrl("c1", "r")).thenReturn(List.of(
                new NoteEntity("c1", "r", "repo:u", "我的笔记", Instant.parse("2026-09-18T11:10:00Z"),
                        Instant.parse("2026-09-18T11:20:00Z"))));
        List<AchievementView> achievements = List.of(
                new AchievementView("FIRST_REPO", "初次探索", "完成第一个仓库分析",
                        Instant.parse("2026-09-18T11:30:00Z")),
                new AchievementView("TEN_QUESTIONS", "刨根问底", "提问 10 次", null));

        ShareSnapshot snapshot = service.build(result(), "graph LR\n  A --> B", "r", "sha",
                "c1", achievements);

        assertThat(snapshot.graphMermaid()).isEqualTo("graph LR\n  A --> B");
        assertThat(snapshot.learningPath()).hasSize(1);
        assertThat(snapshot.learningPath().get(0).codeUnitName()).isEqualTo("A");
        assertThat(snapshot.qaSamples()).hasSize(1);
        assertThat(snapshot.qaSamples().get(0).question()).isEqualTo("问题？");
        assertThat(snapshot.achievements()).hasSize(1);
        assertThat(snapshot.achievements().get(0).code()).isEqualTo("FIRST_REPO");
        assertThat(snapshot.notes()).hasSize(1);
        assertThat(snapshot.notes().get(0).codeUnitName()).isEqualTo("A");
        assertThat(snapshot.notes().get(0).content()).isEqualTo("我的笔记");
    }

    @Test
    @DisplayName("笔记只按分享者 clientId 查询（他人笔记结构性不可见）")
    void notesAreScopedToSharingClient() {
        when(noteRepository.findByClientIdAndRepoUrl(anyString(), anyString())).thenReturn(List.of());

        ShareSnapshot snapshot = service.build(result(), "graph LR", "r", "sha", "c1", List.of());

        assertThat(snapshot.notes()).isEmpty();
        verify(noteRepository).findByClientIdAndRepoUrl("c1", "r");
    }

    @Test
    @DisplayName("过期时间 = 配置天数后")
    void expiresAtFollowsConfig() {
        assertThat(service.expiresAt()).isEqualTo(Instant.parse("2026-10-18T12:00:00Z"));
    }
}
