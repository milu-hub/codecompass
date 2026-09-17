package com.codecompass.web;

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
import com.codecompass.graph.GraphNode;
import com.codecompass.repo.GitRepositoryCloner;
import com.codecompass.service.AnalysisOrchestrator;
import com.codecompass.service.AnalysisTaskSnapshot;
import com.codecompass.service.AnalysisTaskStore;

import static org.mockito.ArgumentMatchers.anyString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 三个端点的契约：状态码、响应结构稳定性、404/409/400 语义。
 *
 * 用 standalone MockMvc + 假编排器 + 真存储 —— 状态由测试直接驱动，不碰网络与线程池。
 */
class RepoControllerTest {

    private AnalysisTaskStore store;
    private GitRepositoryCloner cloner;
    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        store = new AnalysisTaskStore();
        cloner = Mockito.mock(GitRepositoryCloner.class);
        Mockito.when(cloner.validateRepositoryUrl(anyString())).thenReturn(java.util.Optional.empty());

        AnalysisOrchestrator orchestrator = Mockito.mock(AnalysisOrchestrator.class);
        // 假编排器只做"建任务"，不跑真实流水线
        Mockito.when(orchestrator.submit(anyString()))
                .thenAnswer(invocation -> store.create(invocation.getArgument(0)));

        mockMvc = MockMvcBuilders.standaloneSetup(
                new RepoController(cloner, orchestrator, store)).build();
    }

    // ---------- POST /api/repos ----------

    @Test
    @DisplayName("POST 合法 URL：201 + taskId + pending，结构含 language 字段")
    void postValidUrlCreatesPendingTask() throws Exception {
        mockMvc.perform(post("/api/repos")
                        .contentType("application/json")
                        .content("{\"url\": \"https://github.com/spring-projects/spring-petclinic\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.taskId").isNotEmpty())
                .andExpect(jsonPath("$.status").value("pending"))
                .andExpect(jsonPath("$.url").value("https://github.com/spring-projects/spring-petclinic"))
                .andExpect(jsonPath("$.language").value(org.hamcrest.Matchers.nullValue()))
                .andExpect(jsonPath("$.createdAt").isNotEmpty());
    }

    @Test
    @DisplayName("POST 非法 URL：400 + 明确错误，且不建任务")
    void postInvalidUrlReturns400() throws Exception {
        Mockito.when(cloner.validateRepositoryUrl(anyString()))
                .thenReturn(java.util.Optional.of("只支持 https 的 GitHub 仓库地址"));

        mockMvc.perform(post("/api/repos")
                        .contentType("application/json")
                        .content("{\"url\": \"ftp://example.com/x\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("只支持 https 的 GitHub 仓库地址"));

        // 非法 URL 不产生任务（无法通过 store 直接断言，靠 400 语义保证）
    }

    // ---------- GET /api/repos/{id}/status ----------

    @Test
    @DisplayName("status 未知任务：404")
    void statusOfUnknownTaskReturns404() throws Exception {
        mockMvc.perform(get("/api/repos/no-such-task/status"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error").value("任务不存在"));
    }

    @Test
    @DisplayName("status 已知任务：200 + 全字段，language 分析后填充")
    void statusOfKnownTaskReturnsSnapshot() throws Exception {
        String taskId = store.create("https://github.com/a/b");
        store.update(taskId, snapshot -> snapshot.withProgress(
                AnalysisTaskSnapshot.STATUS_RUNNING, 40, "扫描源码"));

        mockMvc.perform(get("/api/repos/" + taskId + "/status"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.taskId").value(taskId))
                .andExpect(jsonPath("$.status").value("running"))
                .andExpect(jsonPath("$.progress").value(40))
                .andExpect(jsonPath("$.message").value("扫描源码"))
                .andExpect(jsonPath("$.language").value(org.hamcrest.Matchers.nullValue()));
    }

    // ---------- GET /api/repos/{id}/graph ----------

    @Test
    @DisplayName("graph 未知任务：404")
    void graphOfUnknownTaskReturns404() throws Exception {
        mockMvc.perform(get("/api/repos/no-such-task/graph"))
                .andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("graph 未完成（running）：409 + 状态说明")
    void graphWhileRunningReturns409() throws Exception {
        String taskId = store.create("https://github.com/a/b");
        store.update(taskId, snapshot -> snapshot.withProgress(
                AnalysisTaskSnapshot.STATUS_RUNNING, 60, "解析源码"));

        mockMvc.perform(get("/api/repos/" + taskId + "/graph"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.taskId").value(taskId))
                .andExpect(jsonPath("$.status").value("running"))
                .andExpect(jsonPath("$.error").isNotEmpty());
    }

    @Test
    @DisplayName("graph done：200 + codeUnits 含 role + mermaid，结构稳定")
    void graphWhenDoneReturnsFullStructure() throws Exception {
        String taskId = store.create("https://github.com/a/b");
        store.update(taskId, snapshot -> snapshot.withDone(doneOutcome(), "java", "分析完成"));

        mockMvc.perform(get("/api/repos/" + taskId + "/graph"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.taskId").value(taskId))
                .andExpect(jsonPath("$.status").value("done"))
                .andExpect(jsonPath("$.language").value("java"))
                .andExpect(jsonPath("$.framework").value("spring"))
                .andExpect(jsonPath("$.codeUnits[0].name").value("OwnerController"))
                .andExpect(jsonPath("$.codeUnits[0].role").value("controller"))
                .andExpect(jsonPath("$.codeUnits[0].annotations[0]").value("@Controller"))
                .andExpect(jsonPath("$.codeUnits[0].startLine").value(1))
                .andExpect(jsonPath("$.dependencies").isArray())
                .andExpect(jsonPath("$.failedFiles").isArray())
                .andExpect(jsonPath("$.isolatedCodeUnitIds").isArray())
                .andExpect(jsonPath("$.mermaid").value(org.hamcrest.Matchers.startsWith("graph LR")));
    }

    @Test
    @DisplayName("graph failed：200 + status=failed + errorMessage")
    void graphWhenFailedReturns200WithError() throws Exception {
        String taskId = store.create("https://github.com/a/b");
        store.update(taskId, snapshot -> snapshot.withFailure("克隆超时（60s）"));

        mockMvc.perform(get("/api/repos/" + taskId + "/graph"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("failed"))
                .andExpect(jsonPath("$.errorMessage").value("克隆超时（60s）"))
                .andExpect(jsonPath("$.codeUnits").isArray())
                .andExpect(jsonPath("$.codeUnits").isEmpty());
    }

    // ---------- helpers ----------

    private AnalysisTaskSnapshot.AnalysisOutcome doneOutcome() {
        CodeUnitInfo unit = new CodeUnitInfo("repo:u", "r", "src/main/java/OwnerController.java",
                "java", "spring", "com.example", "OwnerController", "class",
                List.of("@Controller"), List.of(), 1, 9);
        AnalyzeResult result = new AnalyzeResult("r", "java", "spring",
                List.of(unit), List.of(), List.of(), List.of());
        DependencyGraph graph = new DependencyGraph("r", "java", "spring",
                List.of(new GraphNode(unit.id(), "OwnerController", "com.example.OwnerController",
                        "class", "controller", unit.filePath(), 1, 9)),
                List.of(), List.of(), "graph LR\n  n0[\"OwnerController\"]\n");
        return new AnalysisTaskSnapshot.AnalysisOutcome(result, graph,
                Map.of(unit.id(), "controller"), Map.of(unit.filePath(), List.of("class OwnerController")));
    }
}
