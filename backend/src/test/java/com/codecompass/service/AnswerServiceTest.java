package com.codecompass.service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import com.codecompass.analyzer.AnalyzeResult;
import com.codecompass.analyzer.CodeUnitInfo;
import com.codecompass.analyzer.MethodInfo;
import com.codecompass.graph.DependencyGraph;
import com.codecompass.retrieve.LexicalCodeRetriever;
import com.codecompass.retrieve.RetrieveProperties;
import com.codecompass.testutil.MutableClock;

import tools.jackson.databind.json.JsonMapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

/**
 * T10 问答编排 + T11 缓存与限流接入。
 *
 * 核心契约：行号只能来自检索层 —— LLM 报出的引用逐条与检索片段比对，
 * 不匹配的丢弃并带反馈重试（预算封顶）；输出完全不可解析时降级为纯文本答案。
 * 缓存命中短路 LLM（零计费）、sha 缺失不缓存、超限抛异常。
 * LLM 用 mock（传输层由 {@code OpenAiCompatibleLlmClientTest} 用本地 HTTP 服务实测）。
 */
class AnswerServiceTest {

    private static final String OWNER_FILE = "src/main/java/com/example/OwnerController.java";

    private LlmClient llmClient;
    private AnswerService service;
    private CacheService cacheService;
    private InMemoryRateLimiter rateLimiter;

    @BeforeEach
    void setUp() {
        llmClient = Mockito.mock(LlmClient.class);
        // T13 回归：缓存走 MysqlCacheService（H2 MODE=MySQL 落点）——切 Bean 即换实现的真实闸门
        JdbcTemplate jdbcTemplate = new JdbcTemplate(new DriverManagerDataSource(
                "jdbc:h2:mem:answer-service-test;MODE=MySQL;DB_CLOSE_DELAY=-1", "sa", ""));
        jdbcTemplate.execute("""
                CREATE TABLE IF NOT EXISTS cache_entries (
                  cache_key VARCHAR(64) PRIMARY KEY,
                  value_json TEXT NOT NULL,
                  expires_at DATETIME NOT NULL,
                  created_at DATETIME NOT NULL
                )""");
        jdbcTemplate.execute("DELETE FROM cache_entries");
        cacheService = new MysqlCacheService(jdbcTemplate, JsonMapper.builder().build(),
                new MutableClock(Instant.parse("2026-09-18T00:00:00Z")), new CacheProperties());
        rateLimiter = new InMemoryRateLimiter(new MutableClock(Instant.parse("2026-09-18T00:00:00Z")),
                new RateLimitProperties());
        service = new AnswerService(
                new LexicalCodeRetriever(new RetrieveProperties()),
                llmClient,
                new LlmProperties(),
                JsonMapper.builder().build(),
                cacheService,
                rateLimiter);
    }

    @Test
    @DisplayName("LLM 返回合法 JSON：answer 与引用原样保留，只调用一次")
    void validJsonReturnsAnswerAndReferences() {
        Mockito.when(llmClient.complete(anyString(), anyString())).thenReturn(
                "{\"answer\":\"OwnerController 负责表单提交\",\"references\":[{\"file\":\""
                        + OWNER_FILE + "\",\"language\":\"java\",\"startLine\":1,\"endLine\":9}]}");

        AnswerResponse response = service.ask(doneSnapshot(), "OwnerController 是干什么的", null, "test-client");

        assertThat(response.answer()).isEqualTo("OwnerController 负责表单提交");
        assertThat(response.references())
                .containsExactly(new AnswerResponse.Reference(OWNER_FILE, "java", 1, 9));
        assertThat(response.model()).isEqualTo("gpt-4o-mini");
        verify(llmClient, times(1)).complete(anyString(), anyString());
    }

    @Test
    @DisplayName("引用不匹配：丢弃并带反馈重试，第二次命中后返回合法引用")
    void mismatchedReferencesAreDroppedAndRetried() {
        Mockito.when(llmClient.complete(anyString(), anyString()))
                .thenReturn("{\"answer\":\"A\",\"references\":["
                        + "{\"file\":\"" + OWNER_FILE + "\",\"language\":\"java\",\"startLine\":1,\"endLine\":9},"
                        + "{\"file\":\"" + OWNER_FILE + "\",\"language\":\"java\",\"startLine\":99,\"endLine\":100}]}")
                .thenReturn("{\"answer\":\"A\",\"references\":["
                        + "{\"file\":\"" + OWNER_FILE + "\",\"language\":\"java\",\"startLine\":5,\"endLine\":8}]}");

        AnswerResponse response = service.ask(doneSnapshot(), "OwnerController", null, "test-client");

        assertThat(response.references())
                .containsExactly(new AnswerResponse.Reference(OWNER_FILE, "java", 5, 8));
        verify(llmClient, times(2)).complete(anyString(), anyString());

        ArgumentCaptor<String> prompt = ArgumentCaptor.forClass(String.class);
        verify(llmClient, times(2)).complete(anyString(), prompt.capture());
        assertThat(prompt.getAllValues().get(1))
                .as("第二次提示词必须携带被丢弃的引用清单作为反馈")
                .contains("99", "行号不在任何检索片段内");
    }

    @Test
    @DisplayName("重试预算封顶：始终夹带非法引用时最多调用 maxAttempts 次，合法部分保留")
    void retriesAreCappedAtMaxAttempts() {
        String alwaysPartiallyBad = "{\"answer\":\"A\",\"references\":["
                + "{\"file\":\"" + OWNER_FILE + "\",\"language\":\"java\",\"startLine\":1,\"endLine\":9},"
                + "{\"file\":\"src/main/java/Ghost.java\",\"language\":\"java\",\"startLine\":1,\"endLine\":2}]}";
        Mockito.when(llmClient.complete(anyString(), anyString())).thenReturn(alwaysPartiallyBad);

        AnswerResponse response = service.ask(doneSnapshot(), "OwnerController", null, "test-client");

        assertThat(response.references())
                .containsExactly(new AnswerResponse.Reference(OWNER_FILE, "java", 1, 9));
        verify(llmClient, times(3)).complete(anyString(), anyString());
    }

    @Test
    @DisplayName("检索零命中且无锚点：直接返回提示语，不调用 LLM")
    void emptyRetrievalReturnsFallbackWithoutCallingLlm() {
        AnswerResponse response = service.ask(doneSnapshot(), "zzzz 完全不存在的词", null, "test-client");

        assertThat(response.answer()).contains("未检索到");
        assertThat(response.references()).isEmpty();
        verifyNoInteractions(llmClient);
    }

    @Test
    @DisplayName("LLM 输出完全不可解析：降级为纯文本答案，引用为空，不抛异常")
    void unparseableOutputDegradesToPlainText() {
        Mockito.when(llmClient.complete(anyString(), anyString()))
                .thenReturn("抱歉，我无法用 JSON 回答这个问题。");

        AnswerResponse response = service.ask(doneSnapshot(), "OwnerController", null, "test-client");

        assertThat(response.answer()).isEqualTo("抱歉，我无法用 JSON 回答这个问题。");
        assertThat(response.references()).isEmpty();
    }

    @Test
    @DisplayName("markdown 围栏包裹的 JSON 照常解析")
    void fencedJsonIsStrippedBeforeParsing() {
        Mockito.when(llmClient.complete(anyString(), anyString())).thenReturn(
                "```json\n{\"answer\":\"A\",\"references\":[{\"file\":\"" + OWNER_FILE
                        + "\",\"language\":\"java\",\"startLine\":1,\"endLine\":9}]}\n```");

        AnswerResponse response = service.ask(doneSnapshot(), "OwnerController", null, "test-client");

        assertThat(response.answer()).isEqualTo("A");
        assertThat(response.references())
                .containsExactly(new AnswerResponse.Reference(OWNER_FILE, "java", 1, 9));
    }

    @Test
    @DisplayName("JSON 缺少 answer 字段：整体回退为原文")
    void jsonWithoutAnswerFieldFallsBackToRawText() {
        Mockito.when(llmClient.complete(anyString(), anyString())).thenReturn("{\"references\":[]}");

        AnswerResponse response = service.ask(doneSnapshot(), "OwnerController", null, "test-client");

        assertThat(response.answer()).isEqualTo("{\"references\":[]}");
        assertThat(response.references()).isEmpty();
    }

    @Test
    @DisplayName("LLM 传输失败：LlmException 向上传播（控制器映射 502），不进重试循环")
    void transportFailurePropagates() {
        Mockito.when(llmClient.complete(anyString(), anyString()))
                .thenThrow(new LlmException("LLM 调用失败：HTTP 500"));

        assertThatThrownBy(() -> service.ask(doneSnapshot(), "OwnerController", null, "test-client"))
                .isInstanceOf(LlmException.class)
                .hasMessageContaining("LLM 调用失败");
        verify(llmClient, times(1)).complete(anyString(), anyString());
    }

    @Test
    @DisplayName("提示词携带真实行号前缀与片段信息：中文问题 + 锚点也能触达 LLM")
    void promptCarriesRealLineNumbers() {
        Mockito.when(llmClient.complete(anyString(), anyString()))
                .thenReturn("{\"answer\":\"A\",\"references\":[]}");
        ArgumentCaptor<String> prompt = ArgumentCaptor.forClass(String.class);

        service.ask(doneSnapshot(), "这个类是干什么的", "u-oc", "test-client");

        verify(llmClient).complete(anyString(), prompt.capture());
        assertThat(prompt.getValue())
                .contains(OWNER_FILE)
                .contains("这个类是干什么的")
                .contains("1 | L1")
                .contains("40 | L40");
    }

    @Test
    @DisplayName("startLine/endLine 以字符串给出时也能解析（LLM 常见漂移）")
    void stringLineNumbersAreCoerced() {
        Mockito.when(llmClient.complete(anyString(), anyString())).thenReturn(
                "{\"answer\":\"A\",\"references\":[{\"file\":\"" + OWNER_FILE
                        + "\",\"language\":\"java\",\"startLine\":\"1\",\"endLine\":\"9\"}]}");

        AnswerResponse response = service.ask(doneSnapshot(), "OwnerController", null, "test-client");

        assertThat(response.references())
                .containsExactly(new AnswerResponse.Reference(OWNER_FILE, "java", 1, 9));
    }

    // ---------- T11 缓存与限流 ----------

    @Test
    @DisplayName("缓存命中短路 LLM：同一 key 第二次 ask 不再调用，且零计费")
    void cachedAnswerShortCircuitsLlm() {
        Mockito.when(llmClient.complete(anyString(), anyString())).thenReturn(
                "{\"answer\":\"缓存里的回答\",\"references\":[{\"file\":\"" + OWNER_FILE
                        + "\",\"language\":\"java\",\"startLine\":1,\"endLine\":9}]}");

        AnswerResponse first = service.ask(doneSnapshotWithSha("abc123"), "OwnerController", null, "test-client");
        long usedAfterFirst = rateLimiter.consume("test-client", 0).usedToday();
        assertThat(usedAfterFirst).as("缓存未命中的首次 ask 必须真实计费").isPositive();
        AnswerResponse second = service.ask(doneSnapshotWithSha("abc123"), "OwnerController", null, "test-client");

        assertThat(second).isEqualTo(first);
        verify(llmClient, times(1)).complete(anyString(), anyString());
        assertThat(rateLimiter.consume("test-client", 0).usedToday())
                .as("缓存命中不得再消耗额度")
                .isEqualTo(usedAfterFirst);
    }

    @Test
    @DisplayName("commitSha 缺失：不查不写缓存（宁可 miss 不可错命中）")
    void missingCommitShaSkipsCache() {
        Mockito.when(llmClient.complete(anyString(), anyString())).thenReturn(
                "{\"answer\":\"A\",\"references\":[{\"file\":\"" + OWNER_FILE
                        + "\",\"language\":\"java\",\"startLine\":1,\"endLine\":9}]}");

        service.ask(doneSnapshot(), "OwnerController", null, "test-client");
        service.ask(doneSnapshot(), "OwnerController", null, "test-client");

        verify(llmClient, times(2)).complete(anyString(), anyString());
    }

    @Test
    @DisplayName("超限：抛 RateLimitExceededException 且不触达 LLM")
    void rateLimitExceededThrowsBeforeLlm() {
        RateLimitProperties tiny = new RateLimitProperties();
        tiny.setDailyTokenLimit(1);
        AnswerService tinyBudget = new AnswerService(
                new LexicalCodeRetriever(new RetrieveProperties()),
                llmClient, new LlmProperties(), JsonMapper.builder().build(),
                cacheService, new InMemoryRateLimiter(new MutableClock(Instant.parse("2026-09-18T00:00:00Z")), tiny));

        assertThatThrownBy(() -> tinyBudget.ask(doneSnapshot(), "OwnerController", null, "test-client"))
                .isInstanceOf(RateLimitExceededException.class)
                .hasMessageContaining("token 上限");
        verifyNoInteractions(llmClient);
    }

    @Test
    @DisplayName("成功的 ask 消耗了该客户端的当日额度")
    void successfulAskChargesTokens() {
        Mockito.when(llmClient.complete(anyString(), anyString())).thenReturn(
                "{\"answer\":\"A\",\"references\":[]}");

        service.ask(doneSnapshot(), "OwnerController", null, "test-client");

        assertThat(rateLimiter.consume("test-client", 0).usedToday())
                .as("提示词 token 估算必须已计入该客户端当日用量")
                .isPositive();
    }

    // ---------- 行锚点（选中标识符提问） ----------

    @Test
    @DisplayName("行锚点：聚焦片段置顶进提示词，范围内的引用通过校验")
    void lineAnchoredAskPrependsFocusedSnippet() {
        Mockito.when(llmClient.complete(anyString(), anyString())).thenReturn(
                "{\"answer\":\"A\",\"references\":[{\"file\":\"" + OWNER_FILE
                        + "\",\"language\":\"java\",\"startLine\":12,\"endLine\":14}]}");
        ArgumentCaptor<String> prompt = ArgumentCaptor.forClass(String.class);

        AnswerResponse response = service.ask(doneSnapshot(), "这段代码在做什么",
                "u-oc", 10, 20, "test-client");

        assertThat(response.references())
                .containsExactly(new AnswerResponse.Reference(OWNER_FILE, "java", 12, 14));
        verify(llmClient).complete(anyString(), prompt.capture());
        assertThat(prompt.getValue())
                .as("提示词必须包含聚焦片段（真实行号前缀）")
                .contains("10 | L10")
                .contains("20 | L20")
                .contains("聚焦");
    }

    @Test
    @DisplayName("行锚点范围被钳制在单元范围内（越界不越出类）")
    void lineAnchorIsClampedToUnitRange() {
        Mockito.when(llmClient.complete(anyString(), anyString())).thenReturn("{\"answer\":\"A\",\"references\":[]}");
        ArgumentCaptor<String> prompt = ArgumentCaptor.forClass(String.class);

        service.ask(doneSnapshot(), "这段代码在做什么", "u-oc", 10, 200, "test-client");

        verify(llmClient).complete(anyString(), prompt.capture());
        assertThat(prompt.getValue())
                .contains("40 | L40")
                .doesNotContain("41 |");
    }

    @Test
    @DisplayName("行锚点提问不缓存：同样的问题两次都触达 LLM（行号参与语义，键不含行号会错命中）")
    void lineAnchoredAskSkipsCache() {
        Mockito.when(llmClient.complete(anyString(), anyString())).thenReturn(
                "{\"answer\":\"A\",\"references\":[{\"file\":\"" + OWNER_FILE
                        + "\",\"language\":\"java\",\"startLine\":12,\"endLine\":14}]}");

        service.ask(doneSnapshotWithSha("abc123"), "这段代码在做什么", "u-oc", 10, 20, "test-client");
        service.ask(doneSnapshotWithSha("abc123"), "这段代码在做什么", "u-oc", 10, 20, "test-client");

        verify(llmClient, times(2)).complete(anyString(), anyString());
    }

    @Test
    @DisplayName("行锚点但 unitId 未知：退化为普通检索，不抛异常")
    void lineAnchorWithUnknownUnitFallsBack() {
        Mockito.when(llmClient.complete(anyString(), anyString())).thenReturn("{\"answer\":\"A\",\"references\":[]}");

        AnswerResponse response = service.ask(doneSnapshot(), "OwnerController",
                "ghost-unit", 10, 20, "test-client");

        assertThat(response.answer()).isEqualTo("A");
    }

    // ---------- 夹具 ----------

    private static List<String> numberedLines(int count) {
        List<String> lines = new ArrayList<>();
        for (int i = 1; i <= count; i++) {
            lines.add("L" + i);
        }
        return lines;
    }

    /** done 任务：OwnerController（1..40）+ processFindForm 方法（10..20），快照含源码。 */
    private static AnalysisTaskSnapshot doneSnapshot() {
        return doneSnapshotWithSha(null);
    }

    private static AnalysisTaskSnapshot doneSnapshotWithSha(String commitSha) {
        CodeUnitInfo ownerController = new CodeUnitInfo(
                "u-oc", "repo-1", OWNER_FILE, "java", "spring",
                "com.example", "OwnerController", "class",
                List.of("@Controller"), List.of(), 1, 40);
        MethodInfo processFindForm = new MethodInfo("m-1", "u-oc", "processFindForm",
                "public String processFindForm(Owner owner)", List.of(), 10, 20);

        AnalyzeResult result = new AnalyzeResult("repo-1", "java", "spring",
                List.of(ownerController), List.of(processFindForm), List.of(), List.of());
        DependencyGraph graph = new DependencyGraph("repo-1", "java", "spring",
                List.of(), List.of(), List.of(), "graph LR");

        Map<String, List<String>> sourceLines = new LinkedHashMap<>();
        sourceLines.put(OWNER_FILE, numberedLines(40));

        AnalysisTaskSnapshot.AnalysisOutcome outcome = new AnalysisTaskSnapshot.AnalysisOutcome(
                result, graph, Map.of(), sourceLines, commitSha);

        AnalysisTaskStore store = new AnalysisTaskStore();
        String taskId = store.create("https://github.com/a/b");
        store.update(taskId, snapshot -> snapshot.withDone(outcome, "java", "分析完成"));
        return store.find(taskId).orElseThrow();
    }

    @Test
    @DisplayName("请求头 config 优先：带 requestConfig 用其构造客户端，不带则回落服务端默认")
    void requestConfigOverridesServerDefault() {
        LlmClient mockClient = Mockito.mock(LlmClient.class);
        Mockito.when(mockClient.complete(anyString(), anyString()))
                .thenReturn("{\"answer\":\"A\",\"references\":[]}");

        AtomicReference<LlmConfig> captured = new AtomicReference<>();
        LlmClientFactory factory = config -> {
            captured.set(config);
            return mockClient;
        };
        LlmConfig serverDefault = new LlmConfig("default", "openai",
                "https://api.openai.com/v1", "", "gpt-4o-mini", true);
        AnswerService svc = new AnswerService(
                new LexicalCodeRetriever(new RetrieveProperties()),
                factory, serverDefault,
                new LlmProperties(), JsonMapper.builder().build(),
                cacheService, rateLimiter);

        LlmConfig requestConfig = new LlmConfig("request", "deepseek",
                "https://api.deepseek.com/v1", "sk-test-key", "deepseek-chat", false);

        svc.ask(doneSnapshot(), "OwnerController", null, "test-client", requestConfig);
        assertThat(captured.get()).isEqualTo(requestConfig);

        svc.ask(doneSnapshot(), "OwnerController", null, "test-client");
        assertThat(captured.get()).isEqualTo(serverDefault);
    }
}
