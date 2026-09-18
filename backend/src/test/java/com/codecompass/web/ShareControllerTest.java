package com.codecompass.web;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import com.codecompass.analyzer.AnalyzeResult;
import com.codecompass.analyzer.CodeUnitInfo;
import com.codecompass.graph.DependencyGraph;
import com.codecompass.persistence.ShareSnapshotEntity;
import com.codecompass.persistence.ShareSnapshotRepository;
import com.codecompass.service.AchievementService;
import com.codecompass.service.AnalysisTaskSnapshot;
import com.codecompass.service.AnalysisTaskStore;
import com.codecompass.service.ClientIdentityHolder;
import com.codecompass.service.ShareService;
import com.codecompass.service.ShareSnapshot;

import tools.jackson.databind.json.JsonMapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * F6 分享 REST 契约：生成 404/409/200、短链页 HTML、过期页。
 */
class ShareControllerTest {

    private AnalysisTaskStore store;
    private ShareSnapshotRepository repository;
    private AchievementService achievementService;
    private MockMvc mockMvc;
    private final Clock clock = Clock.fixed(Instant.parse("2026-09-18T12:00:00Z"), ZoneOffset.UTC);

    @BeforeEach
    void setUp() {
        store = new AnalysisTaskStore();
        repository = Mockito.mock(ShareSnapshotRepository.class);
        achievementService = Mockito.mock(AchievementService.class);
        when(achievementService.unlocked(anyString())).thenReturn(List.of());
        ShareService shareService = Mockito.mock(ShareService.class);
        when(shareService.newShareId()).thenReturn("abc123def456");
        when(shareService.expiresAt()).thenReturn(Instant.parse("2026-10-18T12:00:00Z"));
        when(shareService.build(any(), anyString(), anyString(), anyString(), anyString(), any()))
                .thenReturn(new ShareSnapshot("https://github.com/a/b", "sha-1",
                        Instant.parse("2026-09-18T12:00:00Z"), "graph LR\n  A --> B",
                        List.of(), List.of(), List.of()));
        ClientIdentityHolder.set("client-1");
        mockMvc = MockMvcBuilders.standaloneSetup(new ShareController(
                store, shareService, repository, achievementService,
                JsonMapper.builder().build(), clock)).build();
    }

    private String doneTaskId() {
        String taskId = store.create("https://github.com/a/b");
        CodeUnitInfo unit = new CodeUnitInfo("repo:u", "r", "src/A.java", "java", "spring",
                "com.example", "A", "class", List.of(), List.of(), 1, 9);
        AnalyzeResult result = new AnalyzeResult("r", "java", "spring",
                List.of(unit), List.of(), List.of(), List.of());
        DependencyGraph graph = new DependencyGraph("r", "java", "spring",
                List.of(), List.of(), List.of(), "graph LR\n  A --> B");
        store.update(taskId, snapshot -> snapshot.withDone(
                new AnalysisTaskSnapshot.AnalysisOutcome(result, graph, Map.of(), Map.of(), "sha-1"),
                "java", "分析完成"));
        return taskId;
    }

    @Test
    @DisplayName("POST 生成：200 + 短链，落库")
    void createReturnsShareLink() throws Exception {
        String taskId = doneTaskId();

        mockMvc.perform(post("/api/repos/" + taskId + "/share"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.shareId").value("abc123def456"))
                .andExpect(jsonPath("$.url").value("/share/abc123def456"));

        Mockito.verify(repository).save(any(ShareSnapshotEntity.class));
    }

    @Test
    @DisplayName("POST 未知任务：404")
    void createUnknownTaskReturns404() throws Exception {
        mockMvc.perform(post("/api/repos/no-such/share"))
                .andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("GET 短链页：HTML 含依赖图与「由 CodeCompass 生成」")
    void pageRendersHtml() throws Exception {
        ShareSnapshotEntity entity = new ShareSnapshotEntity("abc123def456", "https://github.com/a/b",
                "sha-1",
                "{\"repoUrl\":\"https://github.com/a/b\",\"commitSha\":\"sha-1\","
                        + "\"generatedAt\":\"2026-09-18T12:00:00Z\",\"graphMermaid\":\"graph LR\\n  A --> B\","
                        + "\"learningPath\":[],\"qaSamples\":[],\"achievements\":[]}",
                Instant.parse("2026-10-18T12:00:00Z"), Instant.parse("2026-09-18T12:00:00Z"));
        when(repository.findById("abc123def456")).thenReturn(Optional.of(entity));

        String html = mockMvc.perform(get("/share/abc123def456"))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith("text/html"))
                .andReturn().getResponse().getContentAsString();

        assertThat(html).contains("mermaid", "A --&gt; B", "由 CodeCompass 生成");
    }

    @Test
    @DisplayName("GET 过期快照：404 明确提示")
    void expiredReturns404() throws Exception {
        ShareSnapshotEntity entity = new ShareSnapshotEntity("abc123def456", "https://github.com/a/b",
                "sha-1", "{}", Instant.parse("2026-09-17T12:00:00Z"),
                Instant.parse("2026-09-01T12:00:00Z"));
        when(repository.findById("abc123def456")).thenReturn(Optional.of(entity));

        String html = mockMvc.perform(get("/share/abc123def456"))
                .andExpect(status().isNotFound())
                .andReturn().getResponse().getContentAsString();

        assertThat(html).contains("已过期");
    }
}
