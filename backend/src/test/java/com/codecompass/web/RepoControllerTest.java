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
import com.codecompass.analyzer.DependencyEdge;
import com.codecompass.graph.DependencyGraph;
import com.codecompass.graph.GraphNode;
import com.codecompass.repo.GitRepositoryCloner;
import com.codecompass.retrieve.LexicalCodeRetriever;
import com.codecompass.retrieve.RetrieveProperties;
import com.codecompass.service.AnswerService;
import com.codecompass.service.AnalysisOrchestrator;
import com.codecompass.service.AnalysisTaskSnapshot;
import com.codecompass.service.AnalysisTaskStore;
import com.codecompass.service.LlmClient;
import com.codecompass.service.LlmException;
import com.codecompass.service.LlmProperties;

import tools.jackson.databind.json.JsonMapper;

import static org.mockito.ArgumentMatchers.anyString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 端点契约：状态码、响应结构稳定性、404/409/400 语义。
 *
 * 用 standalone MockMvc + 假编排器 + 真存储 —— 状态由测试直接驱动，不碰网络与线程池。
 * LLM 用 mock（真实 HTTP 契约由 OpenAiCompatibleLlmClientTest 覆盖）。
 */
class RepoControllerTest {

    private AnalysisTaskStore store;
    private GitRepositoryCloner cloner;
    private LlmClient llmClient;
    private MockMvc mockMvc;
    private String seededTaskId;

    @BeforeEach
    void setUp() {
        store = new AnalysisTaskStore();
        cloner = Mockito.mock(GitRepositoryCloner.class);
        Mockito.when(cloner.validateRepositoryUrl(anyString())).thenReturn(java.util.Optional.empty());

        AnalysisOrchestrator orchestrator = Mockito.mock(AnalysisOrchestrator.class);
        // 假编排器只做"建任务"，不跑真实流水线
        Mockito.when(orchestrator.submit(anyString()))
                .thenAnswer(invocation -> store.create(invocation.getArgument(0)));

        llmClient = Mockito.mock(LlmClient.class);
        AnswerService answerService = new AnswerService(
                new LexicalCodeRetriever(new RetrieveProperties()),
                llmClient, new LlmProperties(), JsonMapper.builder().build());

        mockMvc = MockMvcBuilders.standaloneSetup(new RepoController(
                cloner, orchestrator, store,
                new com.codecompass.graph.DependencyGraphBuilder(
                        new com.codecompass.graph.MermaidRenderer()),
                answerService)).build();
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

    // ---------- POST /api/repos/{id}/ask ----------

    @Test
    @DisplayName("ask 未知任务：404")
    void askOfUnknownTaskReturns404() throws Exception {
        mockMvc.perform(post("/api/repos/no-such-task/ask")
                        .contentType("application/json")
                        .content("{\"question\": \"hi\"}"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error").value("任务不存在"));
    }

    @Test
    @DisplayName("ask 空问题：400，不触达任何下游")
    void askWithBlankQuestionReturns400() throws Exception {
        String taskId = store.create("https://github.com/a/b");
        store.update(taskId, snapshot -> snapshot.withDone(doneOutcome(), "java", "分析完成"));

        mockMvc.perform(post("/api/repos/" + taskId + "/ask")
                        .contentType("application/json")
                        .content("{\"question\": \"  \"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("问题不能为空"));

        Mockito.verifyNoInteractions(llmClient);
    }

    @Test
    @DisplayName("ask 未完成（running）：409 + 状态说明")
    void askWhileRunningReturns409() throws Exception {
        String taskId = store.create("https://github.com/a/b");
        store.update(taskId, snapshot -> snapshot.withProgress(
                AnalysisTaskSnapshot.STATUS_RUNNING, 60, "解析源码"));

        mockMvc.perform(post("/api/repos/" + taskId + "/ask")
                        .contentType("application/json")
                        .content("{\"question\": \"hi\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.taskId").value(taskId))
                .andExpect(jsonPath("$.status").value("running"))
                .andExpect(jsonPath("$.error").isNotEmpty());
    }

    @Test
    @DisplayName("ask 分析失败：409 + 携带 errorMessage")
    void askWhenFailedReturns409WithErrorDetail() throws Exception {
        String taskId = store.create("https://github.com/a/b");
        store.update(taskId, snapshot -> snapshot.withFailure("克隆超时（60s）"));

        mockMvc.perform(post("/api/repos/" + taskId + "/ask")
                        .contentType("application/json")
                        .content("{\"question\": \"hi\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.status").value("failed"))
                .andExpect(jsonPath("$.error")
                        .value(org.hamcrest.Matchers.containsString("克隆超时")));
    }

    @Test
    @DisplayName("ask done + LLM 正常：200 + answer/references/model")
    void askWhenDoneReturnsAnswerWithReferences() throws Exception {
        String taskId = store.create("https://github.com/a/b");
        store.update(taskId, snapshot -> snapshot.withDone(doneOutcome(), "java", "分析完成"));
        Mockito.when(llmClient.complete(anyString(), anyString())).thenReturn(
                "{\"answer\":\"OwnerController 负责表单提交\",\"references\":[{\"file\":"
                        + "\"src/main/java/OwnerController.java\",\"language\":\"java\","
                        + "\"startLine\":1,\"endLine\":9}]}");

        mockMvc.perform(post("/api/repos/" + taskId + "/ask")
                        .contentType("application/json")
                        .content("{\"question\": \"OwnerController 是干什么的\", \"unitId\": \"repo:u\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.answer").value("OwnerController 负责表单提交"))
                .andExpect(jsonPath("$.references[0].file").value("src/main/java/OwnerController.java"))
                .andExpect(jsonPath("$.references[0].language").value("java"))
                .andExpect(jsonPath("$.references[0].startLine").value(1))
                .andExpect(jsonPath("$.references[0].endLine").value(9))
                .andExpect(jsonPath("$.model").value("gpt-4o-mini"));
    }

    @Test
    @DisplayName("ask 检索零命中：200 + 提示语，不调用 LLM")
    void askWithNoLexicalHitsReturnsFallback() throws Exception {
        String taskId = store.create("https://github.com/a/b");
        store.update(taskId, snapshot -> snapshot.withDone(doneOutcome(), "java", "分析完成"));

        mockMvc.perform(post("/api/repos/" + taskId + "/ask")
                        .contentType("application/json")
                        .content("{\"question\": \"zzzz 无交集的词\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.answer")
                        .value(org.hamcrest.Matchers.containsString("未检索到")))
                .andExpect(jsonPath("$.references").isEmpty());

        Mockito.verifyNoInteractions(llmClient);
    }

    @Test
    @DisplayName("ask LLM 失败：502 + 明确错误")
    void askWhenLlmFailsReturns502() throws Exception {
        String taskId = store.create("https://github.com/a/b");
        store.update(taskId, snapshot -> snapshot.withDone(doneOutcome(), "java", "分析完成"));
        Mockito.when(llmClient.complete(anyString(), anyString()))
                .thenThrow(new LlmException("LLM 未配置"));

        mockMvc.perform(post("/api/repos/" + taskId + "/ask")
                        .contentType("application/json")
                        .content("{\"question\": \"OwnerController\"}"))
                .andExpect(status().isBadGateway())
                .andExpect(jsonPath("$.error")
                        .value(org.hamcrest.Matchers.containsString("LLM 未配置")));
    }

    // ---------- helpers ----------

    /**
     * 造一个 done 任务：4 个单元 Alpha→Beta→Gamma→Delta 的链。
     * 单元 id 用 id-a/id-b/id-c/id-d，便于邻域断言。
     */
    private void seedDoneWithChain() {
        seededTaskId = store.create("https://github.com/a/b");
        CodeUnitInfo alpha = unit("id-a", "Alpha");
        CodeUnitInfo beta = unit("id-b", "Beta");
        CodeUnitInfo gamma = unit("id-c", "Gamma");
        CodeUnitInfo delta = unit("id-d", "Delta");

        List<DependencyEdge> edges = List.of(
                edge("id-a", "id-b"), edge("id-b", "id-c"), edge("id-c", "id-d"));
        AnalyzeResult result = new AnalyzeResult("r", "java", "spring",
                List.of(alpha, beta, gamma, delta), List.of(), edges, List.of());
        DependencyGraph graph = new DependencyGraph("r", "java", "spring",
                List.of(node(alpha), node(beta), node(gamma), node(delta)),
                edges, List.of(), "graph LR\n  n0[\"Alpha\"]\n  n0 --> n1\n");
        store.update(seededTaskId, snapshot -> snapshot.withDone(
                new AnalysisTaskSnapshot.AnalysisOutcome(result, graph, Map.of(), Map.of()),
                "java", "分析完成"));
    }

    private static CodeUnitInfo unit(String id, String name) {
        return new CodeUnitInfo(id, "r", "src/main/java/" + name + ".java",
                "java", "spring", "com.example", name, "class", List.of(), List.of(), 1, 9);
    }

    private static com.codecompass.graph.GraphNode node(CodeUnitInfo unit) {
        return new com.codecompass.graph.GraphNode(unit.id(), unit.name(),
                unit.packageName() + "." + unit.name(), unit.kind(), "", unit.filePath(), 1, 9);
    }

    private static DependencyEdge edge(String fromId, String toId) {
        return new DependencyEdge(fromId + "->" + toId, "r", fromId, toId, "field", "java");
    }

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

    @Test
    @DisplayName("graph?unit=X&depth=1 返回邻域：codeUnits 仍全量，边与 mermaid 只含邻域")
    void graphWithUnitParamReturnsNeighborhood() throws Exception {
        seedDoneWithChain();

        mockMvc.perform(get("/api/repos/" + seededTaskId + "/graph")
                        .param("unit", "id-b").param("depth", "1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.codeUnits.length()").value(4))
                .andExpect(jsonPath("$.dependencies.length()").value(2))
                .andExpect(jsonPath("$.mermaid").value(org.hamcrest.Matchers.containsString("Alpha")))
                .andExpect(jsonPath("$.mermaid").value(org.hamcrest.Matchers.containsString("Gamma")))
                .andExpect(jsonPath("$.mermaid")
                        .value(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("Delta"))));
    }

    @Test
    @DisplayName("graph?unit=未知单元 返回空邻域：依赖为空 + 占位 mermaid")
    void graphWithUnknownUnitReturnsEmptyNeighborhood() throws Exception {
        seedDoneWithChain();

        mockMvc.perform(get("/api/repos/" + seededTaskId + "/graph").param("unit", "id-ghost"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.codeUnits.length()").value(4))
                .andExpect(jsonPath("$.dependencies").isEmpty())
                .andExpect(jsonPath("$.mermaid")
                        .value(org.hamcrest.Matchers.containsString("该范围内没有依赖关系")));
    }
}
