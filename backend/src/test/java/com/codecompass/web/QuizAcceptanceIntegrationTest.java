package com.codecompass.web;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * F4 端到端验收：真实 petclinic + 真实 LLM 生成测验 → ≥5 题、reference 全部落在
 * 选中类行区间内、提交返回正确率。需要网络与 LLM key，@Tag("integration")。
 */
@Tag("integration")
@EnabledIfEnvironmentVariable(named = "CODESCOMPASS_LLM_API_KEY", matches = ".+")
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
                "codecompass.llm.base-url=https://api.deepseek.com/v1",
                "codecompass.llm.model=deepseek-chat",
                "codecompass.rate-limit.daily-token-limit=10000000"})
class QuizAcceptanceIntegrationTest {

    private static final String PETCLINIC = "https://github.com/spring-projects/spring-petclinic";

    @LocalServerPort
    private int port;

    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(20))
            .build();

    @Test
    @DisplayName("真实 petclinic：生成 ≥5 题、reference 全部合法、提交返回正确率")
    void quizAcceptance() throws Exception {
        String taskId = submitAndAwaitDone();
        JsonMapper mapper = JsonMapper.builder().build();

        JsonNode graph = mapper.readTree(get("/api/repos/" + taskId + "/graph").body());
        String ownerId = null;
        String foundFile = null;
        int foundStart = 0;
        int foundEnd = 0;
        for (JsonNode unit : graph.path("codeUnits")) {
            if (unit.path("name").asText().equals("OwnerController")) {
                ownerId = unit.path("id").asText();
                foundFile = unit.path("filePath").asText();
                foundStart = unit.path("startLine").asInt();
                foundEnd = unit.path("endLine").asInt();
                break;
            }
        }
        assertThat(ownerId).isNotNull();
        final String ownerFile = foundFile;
        final int ownerStart = foundStart;
        final int ownerEnd = foundEnd;

        HttpResponse<String> generate = post("/api/repos/" + taskId + "/quiz",
                "{\"codeUnitIds\":[\"" + ownerId + "\"]}");
        assertThat(generate.statusCode()).isEqualTo(200);
        JsonNode quiz = mapper.readTree(generate.body());

        assertThat(quiz.path("questions").size()).isBetween(5, 10);
        assertThat(quiz.path("questions")).allSatisfy(question -> {
            String file = question.path("reference").path("file").asText();
            int start = question.path("reference").path("startLine").asInt();
            int end = question.path("reference").path("endLine").asInt();
            assertThat(file).as("reference 必须落在选中类文件内").isEqualTo(ownerFile);
            assertThat(start).as("reference 起始行不得越界").isGreaterThanOrEqualTo(ownerStart);
            assertThat(end).as("reference 结束行不得越界").isLessThanOrEqualTo(ownerEnd);
        });

        // 提交（全选 0），断言返回正确率字段
        StringBuilder answers = new StringBuilder("{\"answers\":[");
        for (int i = 0; i < quiz.path("questions").size(); i++) {
            if (i > 0) {
                answers.append(',');
            }
            answers.append("{\"questionId\":\"").append(quiz.path("questions").get(i).path("id").asText())
                    .append("\",\"answerIndex\":0}");
        }
        answers.append("]}");
        HttpResponse<String> submit = post("/api/quizzes/" + quiz.path("id").asText() + "/submit",
                answers.toString());
        assertThat(submit.statusCode()).isEqualTo(200);
        JsonNode grade = mapper.readTree(submit.body());
        assertThat(grade.path("total").asInt()).isEqualTo(quiz.path("questions").size());
        assertThat(grade.path("accuracy").asDouble()).isBetween(0.0, 1.0);
    }

    private String submitAndAwaitDone() throws Exception {
        HttpResponse<String> submit = post("/api/repos", "{\"url\": \"" + PETCLINIC + "\"}");
        assertThat(submit.statusCode()).isEqualTo(201);
        String taskId = JsonMapper.builder().build()
                .readTree(submit.body()).get("taskId").asString();
        long deadline = System.nanoTime() + Duration.ofSeconds(120).toNanos();
        while (System.nanoTime() < deadline) {
            HttpResponse<String> status = get("/api/repos/" + taskId + "/status");
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
                .POST(HttpRequest.BodyPublishers.ofString(body == null ? "" : body)).build(),
                HttpResponse.BodyHandlers.ofString());
    }
}
