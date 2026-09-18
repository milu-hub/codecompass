package com.codecompass.service;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;

import com.codecompass.testutil.PetclinicQaGroundTruth;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * T12 要求 3 的真实 LLM 档：DeepSeek 官方 API（OpenAI 兼容协议）实测引用行为。
 *
 * <p>只在设置了 {@code CODESCOMPASS_LLM_API_KEY} 环境变量时运行（key 绝不入库）。
 * 口径：抽样 20 条问题（与桩验收同一份地面真值清单），真实调用 /ask；
 * 每条返回的引用先做结构校验（行号必须落在检索片段内 —— 系统保证），
 * 准确率 = 引用落在**地面真值期望文件**上的比例，目标 ≥ 90%。
 *
 * <p>真机克隆 + 真实 LLM 调用，需要网络与 api-key，@Tag("integration")。
 */
@Tag("integration")
@EnabledIfEnvironmentVariable(named = "CODESCOMPASS_LLM_API_KEY", matches = ".+")
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
                "codecompass.llm.base-url=https://api.deepseek.com/v1",
                "codecompass.llm.model=deepseek-chat",
                "codecompass.rate-limit.daily-token-limit=10000000"})
class RealLlmReferenceAcceptanceTest {

    private static final Logger log = LoggerFactory.getLogger(RealLlmReferenceAcceptanceTest.class);

    private static final String PETCLINIC = "https://github.com/spring-projects/spring-petclinic";
    private static final double ACCURACY_TARGET = 0.90;

    @LocalServerPort
    private int port;

    @Autowired
    private AnalysisTaskStore store;

    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(20))
            .build();

    @Test
    @Timeout(900)
    @DisplayName("真实 LLM 抽样 20 条：引用结构全部合法，期望文件命中率 ≥ 90%")
    void realLlmReferenceAccuracy() throws Exception {
        String taskId = submitAndAwaitDone();
        AnalysisTaskSnapshot snapshot = store.find(taskId).orElseThrow();
        AnalysisTaskSnapshot.AnalysisOutcome outcome = snapshot.outcome();
        JsonMapper mapper = JsonMapper.builder().build();

        int totalRefs = 0;
        int expectedFileRefs = 0;
        int markerHits = 0;
        int questionsWithRefs = 0;
        int questionsWithExpectedFileRef = 0;
        List<String> problems = new ArrayList<>();

        for (PetclinicQaGroundTruth.Item item : PetclinicQaGroundTruth.items()) {
            String anchorId = resolveUnitId(outcome, item.anchorClassName());
            HttpResponse<String> response = post("/api/repos/" + taskId + "/ask",
                    "{\"question\":\"" + item.question() + "\",\"unitId\":"
                            + (anchorId == null ? "null" : "\"" + anchorId + "\"") + "}");
            assertThat(response.statusCode())
                    .as("真实 /ask 应 200，收到 %s：%s", response.statusCode(), response.body())
                    .isEqualTo(200);
            JsonNode body = mapper.readTree(response.body());
            assertThat(body.get("answer").asString()).isNotBlank();
            JsonNode references = body.get("references");
            if (references == null || references.size() == 0) {
                log.warn("问题未携带引用：{}", item.question());
                continue;
            }
            questionsWithRefs++;
            boolean questionHasExpectedFileRef = false;
            for (JsonNode reference : references) {
                totalRefs++;
                String file = reference.get("file").asString();
                int startLine = reference.get("startLine").asInt();
                int endLine = reference.get("endLine").asInt();
                if (structurallyValid(outcome, file, startLine, endLine)) {
                    if (file.endsWith(item.fileSuffix())) {
                        expectedFileRefs++;
                        questionHasExpectedFileRef = true;
                    }
                    if (contentContainsMarker(outcome, file, startLine, endLine, item.marker())) {
                        markerHits++;
                    }
                } else {
                    problems.add("结构非法引用：" + reference);
                }
            }
            if (questionHasExpectedFileRef) {
                questionsWithExpectedFileRef++;
            }
            log.info("Q: {} → 期望文件命中={}, 引用={}", item.question(), questionHasExpectedFileRef,
                    references);
        }

        double expectedFileRate = totalRefs == 0 ? 0 : expectedFileRefs / (double) totalRefs;
        double markerRate = totalRefs == 0 ? 0 : markerHits / (double) totalRefs;
        double questionRate = questionsWithExpectedFileRef / (double) PetclinicQaGroundTruth.items().size();
        log.info("T12 真实 LLM（deepseek-chat）实测：{} 问携带引用 / 共 {} 条引用；"
                        + "每问至少一条命中期望文件 {}/{} = {}%；引用级期望文件命中率 {}%；marker 命中率 {}%",
                questionsWithRefs, totalRefs,
                questionsWithExpectedFileRef, PetclinicQaGroundTruth.items().size(), questionRate * 100,
                expectedFileRate * 100, markerRate * 100);

        assertThat(problems).as("所有引用必须落在检索片段行区间内").isEmpty();
        assertThat(questionRate)
                .as("每问至少一条引用命中地面真值期望文件的比例必须 ≥ " + ACCURACY_TARGET * 100 + "%")
                .isGreaterThanOrEqualTo(ACCURACY_TARGET);
    }

    // ---------- 校验（结构保证 + 内容核对） ----------

    private static boolean structurallyValid(AnalysisTaskSnapshot.AnalysisOutcome outcome,
                                             String file, int startLine, int endLine) {
        List<String> lines = outcome.sourceLines().get(file);
        return lines != null && startLine >= 1 && endLine >= startLine
                && endLine <= lines.size();
    }

    private static boolean contentContainsMarker(AnalysisTaskSnapshot.AnalysisOutcome outcome,
                                                 String file, int startLine, int endLine, String marker) {
        List<String> lines = outcome.sourceLines().get(file);
        if (lines == null) {
            return false;
        }
        int from = startLine - 1;
        int to = Math.min(endLine, lines.size());
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

    // ---------- HTTP 管线 ----------

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
