package com.codecompass.service;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;

import com.codecompass.retrieve.LexicalCodeRetriever;
import com.codecompass.retrieve.RetrieveProperties;
import com.codecompass.testutil.MutableClock;
import com.codecompass.testutil.PetclinicQaGroundTruth;

import tools.jackson.databind.json.JsonMapper;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * T12 要求 3：问答引用行号准确率 ≥ 90%。
 *
 * <p><b>地面真值独立于被测代码</b>：问题集手写（{@link PetclinicQaGroundTruth}），
 * 每条引用 = 期望文件 + 源码文本标记（marker），由**源码快照文本**定位行号 ——
 * 不经解析器、不经检索层，避免自己证明自己。桩 LLM 回放地面真值引用；
 * 准确率 = 通过校验（即检索层带回了覆盖该行的片段）的引用比例。
 *
 * <p>真机克隆 petclinic，需要网络，@Tag("integration")。
 */
@Tag("integration")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class QaReferenceAccuracyAcceptanceTest {

    private static final Logger log = LoggerFactory.getLogger(QaReferenceAccuracyAcceptanceTest.class);

    private static final String PETCLINIC = "https://github.com/spring-projects/spring-petclinic";
    private static final double ACCURACY_TARGET = 0.90;

    @LocalServerPort
    private int port;

    @Autowired
    private AnalysisTaskStore store;

    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .build();

    @Test
    @DisplayName("抽样 20 条：引用逐条通过检索校验且内容命中 marker，准确率 ≥ 90%")
    void referenceLineAccuracyAcceptance() throws Exception {
        String taskId = submitAndAwaitDone();
        AnalysisTaskSnapshot snapshot = store.find(taskId).orElseThrow();
        AnalysisTaskSnapshot.AnalysisOutcome outcome = snapshot.outcome();

        StubLlmClient stubLlm = new StubLlmClient();
        AnswerService service = new AnswerService(
                new LexicalCodeRetriever(new RetrieveProperties()),
                stubLlm,
                new LlmProperties(),
                JsonMapper.builder().build(),
                new InMemoryCacheService(new MutableClock(Instant.parse("2026-09-18T00:00:00Z")),
                        new CacheProperties()),
                new InMemoryRateLimiter(new MutableClock(Instant.parse("2026-09-18T00:00:00Z")),
                        generousRateProperties()));

        int passed = 0;
        List<String> failures = new ArrayList<>();
        for (PetclinicQaGroundTruth.Item item : PetclinicQaGroundTruth.items()) {
            AnswerResponse.Reference groundTruth = locateMarker(outcome, item);
            stubLlm.canned = cannedJson(groundTruth);
            AnswerResponse response = service.ask(
                    snapshot, item.question(), resolveUnitId(outcome, item.anchorClassName()), "acceptance");

            boolean accepted = response.references().contains(groundTruth)
                    && contentContainsMarker(outcome, groundTruth, item.marker());
            if (accepted) {
                passed++;
            } else {
                failures.add(item.question() + " → " + groundTruth);
            }
        }

        double accuracy = passed / (double) PetclinicQaGroundTruth.items().size();
        log.info("T12 引用行号准确率实测：{}/{} = {}%", passed, PetclinicQaGroundTruth.items().size(), accuracy * 100);
        assertThat(accuracy)
                .as("准确率必须 ≥ " + ACCURACY_TARGET * 100 + "%，未通过条目：" + failures)
                .isGreaterThanOrEqualTo(ACCURACY_TARGET);
    }

    // ---------- 地面真值（独立于解析器与检索层） ----------

    private static AnswerResponse.Reference locateMarker(AnalysisTaskSnapshot.AnalysisOutcome outcome,
                                                         PetclinicQaGroundTruth.Item item) {
        Map.Entry<String, List<String>> file = outcome.sourceLines().entrySet().stream()
                .filter(entry -> entry.getKey().endsWith(item.fileSuffix()))
                .findFirst()
                .orElseThrow(() -> new AssertionError("地面真值标注有误：快照缺文件 " + item.fileSuffix()));
        for (int i = 0; i < file.getValue().size(); i++) {
            if (file.getValue().get(i).contains(item.marker())) {
                return new AnswerResponse.Reference(file.getKey(), "java", i + 1, i + 1);
            }
        }
        throw new AssertionError("地面真值标注有误：" + item.fileSuffix() + " 无 marker「" + item.marker() + "」");
    }

    private static boolean contentContainsMarker(AnalysisTaskSnapshot.AnalysisOutcome outcome,
                                                 AnswerResponse.Reference reference, String marker) {
        List<String> lines = outcome.sourceLines().get(reference.file());
        if (lines == null) {
            return false;
        }
        int from = reference.startLine() - 1;
        int to = Math.min(reference.endLine(), lines.size());
        return from >= 0 && from < to
                && lines.subList(from, to).stream().anyMatch(line -> line.contains(marker));
    }

    private static String resolveUnitId(AnalysisTaskSnapshot.AnalysisOutcome outcome, String anchorClassName) {
        if (anchorClassName == null) {
            return null;
        }
        return outcome.result().codeUnits().stream()
                .filter(unit -> unit.name().equals(anchorClassName))
                .map(unit -> unit.id())
                .findFirst()
                .orElseThrow(() -> new AssertionError("地面真值标注有误：找不到类 " + anchorClassName));
    }

    private static RateLimitProperties generousRateProperties() {
        RateLimitProperties properties = new RateLimitProperties();
        properties.setDailyTokenLimit(10_000_000);
        return properties;
    }

    private static String cannedJson(AnswerResponse.Reference reference) {
        return "{\"answer\":\"测试桩回答\",\"references\":[{\"file\":\"" + reference.file()
                + "\",\"language\":\"" + reference.language()
                + "\",\"startLine\":" + reference.startLine()
                + ",\"endLine\":" + reference.endLine() + "}]}";
    }

    /** 桩 LLM：只回放地面真值引用，绝不从检索结果派生 —— 否则验收变成自己证明自己。 */
    private static final class StubLlmClient implements LlmClient {
        private String canned = "{}";

        @Override
        public String complete(String systemPrompt, String userPrompt) {
            return canned;
        }
    }

    // ---------- HTTP 管线（同 AnalysisPipelineIntegrationTest 的轮询模式） ----------

    private String submitAndAwaitDone() throws Exception {
        HttpResponse<String> submit = post("/api/repos", "{\"url\": \"" + PETCLINIC + "\"}");
        assertThat(submit.statusCode()).isEqualTo(201);
        String taskId = JsonMapper.builder().build()
                .readTree(submit.body()).get("taskId").asString();
        long deadline = System.nanoTime() + Duration.ofSeconds(120).toNanos();
        while (System.nanoTime() < deadline) {
            HttpResponse<String> status = get("/api/repos/" + taskId + "/status");
            assertThat(status.statusCode()).isEqualTo(200);
            String state = JsonMapper.builder().build()
                    .readTree(status.body()).get("status").asString();
            if (state.equals("done")) {
                return taskId;
            }
            if (state.equals("failed")) {
                throw new AssertionError("分析失败：" + status.body());
            }
            Thread.sleep(500);
        }
        throw new AssertionError("任务未在期限内结束");
    }

    private HttpResponse<String> get(String path) throws Exception {
        return httpClient.send(HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
                .header("Accept", "application/json").GET().build(),
                HttpResponse.BodyHandlers.ofString());
    }

    private HttpResponse<String> post(String path, String body) throws Exception {
        return httpClient.send(HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
                .header("Content-Type", "application/json")
                .header("Accept", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body)).build(),
                HttpResponse.BodyHandlers.ofString());
    }
}
