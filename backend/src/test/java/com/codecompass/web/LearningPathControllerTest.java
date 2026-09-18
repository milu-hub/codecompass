package com.codecompass.web;

import java.time.Instant;
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
import com.codecompass.persistence.LearningPathEntity;
import com.codecompass.persistence.LearningPathRepository;
import com.codecompass.service.AnalysisTaskSnapshot;
import com.codecompass.service.AnalysisTaskStore;
import com.codecompass.service.LearningPathService;
import com.codecompass.service.LlmClient;
import com.codecompass.service.LlmException;

import tools.jackson.databind.json.JsonMapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 学习路线 REST 契约：404/409、生成、幂等（命中库不调 LLM）、未生成 404。
 */
class LearningPathControllerTest {

    private AnalysisTaskStore store;
    private LearningPathRepository repository;
    private LlmClient llmClient;
    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        store = new AnalysisTaskStore();
        repository = Mockito.mock(LearningPathRepository.class);
        llmClient = Mockito.mock(LlmClient.class);
        Mockito.when(llmClient.complete(anyString(), anyString()))
                .thenThrow(new LlmException("LLM 未配置"));   // 缺省走 fallback reason
        LearningPathService service = new LearningPathService(llmClient, JsonMapper.builder().build());
        mockMvc = MockMvcBuilders.standaloneSetup(new LearningPathController(
                store, service, repository, JsonMapper.builder().build())).build();
    }

    private String doneTaskId() {
        String taskId = store.create("https://github.com/a/b");
        CodeUnitInfo unit = new CodeUnitInfo("repo:u", "r", "src/A.java", "java", "spring",
                "com.example", "A", "class", List.of(), List.of(), 1, 9);
        AnalyzeResult result = new AnalyzeResult("r", "java", "spring",
                List.of(unit), List.of(), List.of(), List.of());
        DependencyGraph graph = new DependencyGraph("r", "java", "spring",
                List.of(), List.of(), List.of(), "graph LR");
        store.update(taskId, snapshot -> snapshot.withDone(
                new AnalysisTaskSnapshot.AnalysisOutcome(result, graph, Map.of("repo:u", "entry"), Map.of(), "sha-1"),
                "java", "分析完成"));
        return taskId;
    }

    @Test
    @DisplayName("POST 未知任务：404")
    void generateUnknownTaskReturns404() throws Exception {
        mockMvc.perform(post("/api/repos/no-such/learning-path"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error").value("任务不存在"));
    }

    @Test
    @DisplayName("POST 未完成：409")
    void generateWhileRunningReturns409() throws Exception {
        String taskId = store.create("https://github.com/a/b");
        store.update(taskId, snapshot -> snapshot.withProgress(
                AnalysisTaskSnapshot.STATUS_RUNNING, 60, "解析源码"));

        mockMvc.perform(post("/api/repos/" + taskId + "/learning-path"))
                .andExpect(status().isConflict());
    }

    @Test
    @DisplayName("POST 生成：200 + 步骤有序 + reason 非空，并落库")
    void generateCreatesAndReturnsPath() throws Exception {
        String taskId = doneTaskId();
        Mockito.when(repository.findByRepoUrlAndCommitSha(anyString(), anyString()))
                .thenReturn(Optional.empty());

        mockMvc.perform(post("/api/repos/" + taskId + "/learning-path"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.repoUrl").value("https://github.com/a/b"))
                .andExpect(jsonPath("$.commitSha").value("sha-1"))
                .andExpect(jsonPath("$.steps[0].order").value(1))
                .andExpect(jsonPath("$.steps[0].codeUnitId").value("repo:u"))
                .andExpect(jsonPath("$.steps[0].reason").isNotEmpty());

        verify(repository).saveAndFlush(any());
    }

    @Test
    @DisplayName("POST 幂等：库里已有 → 直接返回，不调 LLM、不落库")
    void generateIsIdempotentWhenStored() throws Exception {
        String taskId = doneTaskId();
        String storedJson = "{\"repoUrl\":\"https://github.com/a/b\",\"commitSha\":\"sha-1\","
                + "\"steps\":[{\"order\":1,\"codeUnitId\":\"repo:u\",\"reason\":\"先读入口\",\"estimatedMinutes\":5}]}";
        Mockito.when(repository.findByRepoUrlAndCommitSha(anyString(), anyString()))
                .thenReturn(Optional.of(new LearningPathEntity(
                        "https://github.com/a/b", "sha-1", storedJson, Instant.now())));

        mockMvc.perform(post("/api/repos/" + taskId + "/learning-path"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.steps[0].reason").value("先读入口"));

        verify(llmClient, never()).complete(anyString(), anyString());
        verify(repository, never()).saveAndFlush(any());
    }

    @Test
    @DisplayName("GET 未生成：404")
    void getReturns404WhenAbsent() throws Exception {
        String taskId = doneTaskId();
        Mockito.when(repository.findByRepoUrlAndCommitSha(anyString(), anyString()))
                .thenReturn(Optional.empty());

        mockMvc.perform(get("/api/repos/" + taskId + "/learning-path"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error").value("尚未生成学习路线，请先 POST"));
    }

    @Test
    @DisplayName("GET 已生成：200 + 完整步骤")
    void getReturnsStoredPath() throws Exception {
        String taskId = doneTaskId();
        String storedJson = "{\"repoUrl\":\"https://github.com/a/b\",\"commitSha\":\"sha-1\","
                + "\"steps\":[{\"order\":1,\"codeUnitId\":\"repo:u\",\"reason\":\"先读入口\",\"estimatedMinutes\":5}]}";
        Mockito.when(repository.findByRepoUrlAndCommitSha(anyString(), anyString()))
                .thenReturn(Optional.of(new LearningPathEntity(
                        "https://github.com/a/b", "sha-1", storedJson, Instant.now())));

        mockMvc.perform(get("/api/repos/" + taskId + "/learning-path"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.steps[0].codeUnitId").value("repo:u"));
    }

    @Test
    @DisplayName("POST 生成的 reason 经过 60 字截断（用真实服务 + 长 reason LLM）")
    void generatedReasonIsClamped() throws Exception {
        String taskId = doneTaskId();
        Mockito.when(repository.findByRepoUrlAndCommitSha(anyString(), anyString()))
                .thenReturn(Optional.empty());
        Mockito.doReturn("{\"reasons\":[{\"codeUnitId\":\"repo:u\",\"reason\":\""
                + "很".repeat(100) + "\"}]}")
                .when(llmClient).complete(anyString(), anyString());

        String body = mockMvc.perform(post("/api/repos/" + taskId + "/learning-path"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        assertThat(JsonMapper.builder().build().readTree(body)
                .path("steps").get(0).path("reason").asText()).hasSize(60);
    }
}
