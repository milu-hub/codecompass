package com.codecompass.web;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import com.codecompass.analyzer.AnalyzeResult;
import com.codecompass.analyzer.CodeUnitInfo;
import com.codecompass.graph.DependencyGraph;
import com.codecompass.persistence.QuizEntity;
import com.codecompass.persistence.QuizRepository;
import com.codecompass.service.AnalysisTaskSnapshot;
import com.codecompass.service.AnalysisTaskStore;
import com.codecompass.service.LlmClient;
import com.codecompass.service.QuizService;

import tools.jackson.databind.json.JsonMapper;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 测验 REST 契约：生成 404/409/200、判分。
 */
class QuizControllerTest {

    private AnalysisTaskStore store;
    private QuizRepository repository;
    private LlmClient llmClient;
    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        store = new AnalysisTaskStore();
        repository = Mockito.mock(QuizRepository.class);
        llmClient = Mockito.mock(LlmClient.class);
        QuizService service = new QuizService(llmClient, JsonMapper.builder().build());
        mockMvc = MockMvcBuilders.standaloneSetup(new QuizController(
                store, service, repository,
                Mockito.mock(com.codecompass.service.AchievementService.class),
                JsonMapper.builder().build())).build();
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
                new AnalysisTaskSnapshot.AnalysisOutcome(result, graph, Map.of(),
                        Map.of("src/A.java", List.of("class A")), "sha-1"),
                "java", "分析完成"));
        return taskId;
    }

    @Test
    @DisplayName("生成未知任务：404")
    void generateUnknownTaskReturns404() throws Exception {
        mockMvc.perform(post("/api/repos/no-such/quiz")
                        .contentType("application/json")
                        .content("{\"codeUnitIds\":[\"x\"]}"))
                .andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("生成未完成：409")
    void generateWhileRunningReturns409() throws Exception {
        String taskId = store.create("https://github.com/a/b");
        store.update(taskId, snapshot -> snapshot.withProgress(
                AnalysisTaskSnapshot.STATUS_RUNNING, 60, "解析源码"));

        mockMvc.perform(post("/api/repos/" + taskId + "/quiz")
                        .contentType("application/json")
                        .content("{\"codeUnitIds\":[\"x\"]}"))
                .andExpect(status().isConflict());
    }

    @Test
    @DisplayName("生成合法：200 + 题目被 LLM 校验后返回 + 落库")
    void generateReturnsValidatedQuiz() throws Exception {
        String taskId = doneTaskId();
        when(llmClient.complete(anyString(), anyString())).thenReturn(
                "{\"questions\":[{\"type\":\"true_false\",\"question\":\"A 是入口类吗？\","
                        + "\"options\":[\"正确\",\"错误\"],\"answer\":0,\"explanation\":\"是\","
                        + "\"reference\":{\"file\":\"src/A.java\",\"startLine\":1,\"endLine\":1}}]}");

        mockMvc.perform(post("/api/repos/" + taskId + "/quiz")
                        .contentType("application/json")
                        .content("{\"codeUnitIds\":[\"repo:u\"]}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.questions[0].type").value("true_false"))
                .andExpect(jsonPath("$.questions[0].reference.file").value("src/A.java"));

        Mockito.verify(repository).save(any());
    }

    @Test
    @DisplayName("提交判分：从库读题，返回正确率")
    void submitGradesFromStoredQuiz() throws Exception {
        String quizJson = "{\"id\":\"quiz-1\",\"repoUrl\":\"https://github.com/a/b\",\"commitSha\":\"sha\","
                + "\"questions\":["
                + "{\"id\":\"q0\",\"type\":\"true_false\",\"question\":\"Q1\",\"options\":[\"正确\",\"错误\"],\"answer\":0,\"explanation\":\"E\",\"reference\":{\"file\":\"src/A.java\",\"language\":\"java\",\"startLine\":1,\"endLine\":1}},"
                + "{\"id\":\"q1\",\"type\":\"true_false\",\"question\":\"Q2\",\"options\":[\"正确\",\"错误\"],\"answer\":1,\"explanation\":\"E\",\"reference\":{\"file\":\"src/A.java\",\"language\":\"java\",\"startLine\":1,\"endLine\":1}}]}";
        when(repository.findById("quiz-1")).thenReturn(java.util.Optional.of(
                new QuizEntity("quiz-1", "https://github.com/a/b", "sha", quizJson, Instant.now())));

        mockMvc.perform(post("/api/quizzes/quiz-1/submit")
                        .contentType("application/json")
                        .content("{\"answers\":[{\"questionId\":\"q0\",\"answerIndex\":0},{\"questionId\":\"q1\",\"answerIndex\":0}]}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.correct").value(1))
                .andExpect(jsonPath("$.total").value(2))
                .andExpect(jsonPath("$.accuracy").value(0.5));
    }
}
