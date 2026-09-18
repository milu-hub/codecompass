package com.codecompass.service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.codecompass.analyzer.AnalyzeResult;
import com.codecompass.analyzer.CodeUnitInfo;
import com.codecompass.graph.DependencyGraph;
import com.codecompass.retrieve.LexicalCodeRetriever;
import com.codecompass.retrieve.RetrieveProperties;
import com.codecompass.testutil.MutableClock;

import tools.jackson.databind.json.JsonMapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

/**
 * T12 要求 4：缓存命中响应 P95 < 2 秒（TASKBOOK §04 口径：命中后的 P95）。
 *
 * <p>测量纪律：先真实走一次 LLM 把结果缓存下来 → 预热（吸收 JIT/类加载）→
 * 100 次采样取 P95。同时用 {@code verify(llm, times(1))} 钉死命中路径零 LLM 调用，
 * 否则测的就不是缓存延迟。
 */
class CacheHitLatencyTest {

    private static final Logger log = LoggerFactory.getLogger(CacheHitLatencyTest.class);

    private static final String OWNER_FILE = "src/main/java/com/example/OwnerController.java";
    private static final long P95_LIMIT_MS = 2000;

    @Test
    @DisplayName("缓存命中 P95 < 2 秒，且命中路径不触达 LLM")
    void cacheHitP95UnderTwoSeconds() {
        LlmClient llmClient = Mockito.mock(LlmClient.class);
        Mockito.when(llmClient.complete(anyString(), anyString())).thenReturn(
                "{\"answer\":\"缓存回答\",\"references\":[{\"file\":\"" + OWNER_FILE
                        + "\",\"language\":\"java\",\"startLine\":1,\"endLine\":9}]}");
        AnswerService service = new AnswerService(
                new LexicalCodeRetriever(new RetrieveProperties()),
                llmClient,
                new LlmProperties(),
                JsonMapper.builder().build(),
                new InMemoryCacheService(new MutableClock(Instant.parse("2026-09-18T00:00:00Z")),
                        new CacheProperties()),
                new InMemoryRateLimiter(new MutableClock(Instant.parse("2026-09-18T00:00:00Z")),
                        new RateLimitProperties()));

        AnalysisTaskSnapshot snapshot = doneSnapshotWithSha("benchmark-sha");
        service.ask(snapshot, "OwnerController", null, "bench-client");   // 真实一次 → 入缓存

        for (int i = 0; i < 10; i++) {                                     // 预热
            service.ask(snapshot, "OwnerController", null, "bench-client");
        }

        List<Long> samples = new ArrayList<>();
        for (int i = 0; i < 100; i++) {
            long start = System.nanoTime();
            service.ask(snapshot, "OwnerController", null, "bench-client");
            samples.add((System.nanoTime() - start) / 1_000_000);
        }

        samples.sort(Long::compareTo);
        long p95 = samples.get((int) Math.ceil(samples.size() * 0.95) - 1);
        long max = samples.get(samples.size() - 1);
        log.info("T12 缓存命中实测：P95 = {} ms，max = {} ms（100 次采样）", p95, max);

        assertThat(p95).as("缓存命中 P95 必须 < %d ms", P95_LIMIT_MS).isLessThan(P95_LIMIT_MS);
        verify(llmClient, times(1)).complete(anyString(), anyString());    // 100 次命中零 LLM 调用
    }

    // ---------- 夹具 ----------

    private static AnalysisTaskSnapshot doneSnapshotWithSha(String commitSha) {
        CodeUnitInfo ownerController = new CodeUnitInfo(
                "u-oc", "repo-1", OWNER_FILE, "java", "spring",
                "com.example", "OwnerController", "class",
                List.of("@Controller"), List.of(), 1, 40);
        AnalyzeResult result = new AnalyzeResult("repo-1", "java", "spring",
                List.of(ownerController), List.of(), List.of(), List.of());
        DependencyGraph graph = new DependencyGraph("repo-1", "java", "spring",
                List.of(), List.of(), List.of(), "graph LR");
        Map<String, List<String>> sourceLines = new LinkedHashMap<>();
        List<String> lines = new ArrayList<>();
        for (int i = 1; i <= 40; i++) {
            lines.add("L" + i);
        }
        sourceLines.put(OWNER_FILE, lines);

        AnalysisTaskSnapshot.AnalysisOutcome outcome = new AnalysisTaskSnapshot.AnalysisOutcome(
                result, graph, Map.of(), sourceLines, commitSha);
        AnalysisTaskStore store = new AnalysisTaskStore();
        String taskId = store.create("https://github.com/a/b");
        store.update(taskId, snapshot -> snapshot.withDone(outcome, "java", "分析完成"));
        return store.find(taskId).orElseThrow();
    }
}
