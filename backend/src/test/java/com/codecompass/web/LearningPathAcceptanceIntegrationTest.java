package com.codecompass.web;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * F2 端到端验收：真实 petclinic → 生成学习路线 → 首步入口类、不重复不遗漏、
 * 每步 reason 非空；二次请求幂等。需要网络，@Tag("integration")。
 */
@Tag("integration")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class LearningPathAcceptanceIntegrationTest {

    private static final String PETCLINIC = "https://github.com/spring-projects/spring-petclinic";

    @LocalServerPort
    private int port;

    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .build();

    @Test
    @DisplayName("真实 petclinic：路线首步入库、覆盖全量、reason 非空、二次幂等")
    void learningPathAcceptance() throws Exception {
        String taskId = submitAndAwaitDone();

        JsonNode first = JsonMapper.builder().build()
                .readTree(post("/api/repos/" + taskId + "/learning-path", "").body());
        assertThat(first.path("steps").size()).isGreaterThan(20);
        assertThat(first.path("steps").get(0).path("codeUnitId").asText())
                .as("首步必须是入口类")
                .isNotBlank();

        // 不重复不遗漏：codeUnitId 集合大小 == steps 数量
        int stepCount = first.path("steps").size();
        long distinctUnits = java.util.stream.StreamSupport
                .stream(first.path("steps").spliterator(), false)
                .map(node -> node.path("codeUnitId").asText())
                .distinct().count();
        assertThat(distinctUnits).as("路线不得重复").isEqualTo(stepCount);

        assertThat(first.path("steps")).allSatisfy(step ->
                assertThat(step.path("reason").asText()).as("每步 reason 非空").isNotBlank());

        // 二次请求命中数据库，返回一致
        JsonNode second = JsonMapper.builder().build()
                .readTree(post("/api/repos/" + taskId + "/learning-path", "").body());
        assertThat(second).isEqualTo(first);
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
