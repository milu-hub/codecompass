package com.codecompass.web;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;

import com.codecompass.service.AnalysisTaskSnapshot;
import com.codecompass.service.AnalysisTaskStore;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * T7 端到端验收：POST /api/repos → 轮询 status → GET graph，真实 petclinic。
 *
 * 这是克隆→扫描→分析→构图整条链路第一次以 HTTP 暴露。需要网络，@Tag("integration")。
 */
@Tag("integration")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class AnalysisPipelineIntegrationTest {

    private static final String PETCLINIC = "https://github.com/spring-projects/spring-petclinic";

    @LocalServerPort
    private int port;

    @Autowired
    private AnalysisTaskStore store;

    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .build();

    @Test
    @DisplayName("真实仓库全链路：POST 201 → 轮询 done → graph 完整可渲染，工作区已清理")
    void fullPipelineOverHttp() throws Exception {
        // 1. 提交
        HttpResponse<String> submit = post("/api/repos",
                "{\"url\": \"" + PETCLINIC + "\"}");
        assertThat(submit.statusCode()).isEqualTo(201);
        JsonNode submitBody = JsonMapper.builder().build().readTree(submit.body());
        String taskId = submitBody.get("taskId").asString();
        assertThat(taskId).isNotBlank();
        assertThat(submitBody.get("status").asString()).isEqualTo("pending");

        // 2. 轮询直到终态
        JsonNode finalStatus = pollStatus(taskId, Duration.ofSeconds(120));
        assertThat(finalStatus.get("status").asString()).isEqualTo("done");
        assertThat(finalStatus.get("language").asString()).isEqualTo("java");
        assertThat(finalStatus.get("progress").asInt()).isEqualTo(100);

        // 3. 取图
        HttpResponse<String> graphResponse = get("/api/repos/" + taskId + "/graph");
        assertThat(graphResponse.statusCode()).isEqualTo(200);
        JsonNode graph = JsonMapper.builder().build().readTree(graphResponse.body());

        assertThat(graph.get("status").asString()).isEqualTo("done");
        assertThat(graph.get("language").asString()).isEqualTo("java");
        assertThat(graph.get("framework").asString()).isEqualTo("spring");

        // 类列表：含 OwnerController 且角色正确
        JsonNode units = graph.get("codeUnits");
        assertThat(units.size()).isGreaterThan(20);
        JsonNode ownerController = null;
        for (JsonNode unit : units) {
            if (unit.get("name").asString().equals("OwnerController")) {
                ownerController = unit;
                break;
            }
        }
        assertThat(ownerController).isNotNull();
        assertThat(ownerController.get("role").asString()).isEqualTo("controller");
        assertThat(ownerController.get("packageName").asString())
                .isEqualTo("org.springframework.samples.petclinic.owner");
        assertThat(ownerController.get("startLine").asInt()).isGreaterThanOrEqualTo(1);

        // 图可渲染 + 有边 + 孤立节点被报告
        assertThat(graph.get("mermaid").asString()).startsWith("graph LR").contains("-->");
        assertThat(graph.get("dependencies").size()).isGreaterThan(0);
        assertThat(graph.get("isolatedCodeUnitIds").size()).isGreaterThan(0);
        assertThat(graph.get("failedFiles").size()).isZero();

        // 4. 内容快照已入库（T9/T10 的原料），且工作区已删除（§07 底线）
        AnalysisTaskSnapshot snapshot = store.find(taskId).orElseThrow();
        assertThat(snapshot.outcome().sourceLines())
                .anySatisfy((path, lines) -> {
                    if (path.contains("OwnerController.java")) {
                        assertThat(lines).anyMatch(line -> line.contains("class OwnerController"));
                    }
                });
        assertThat(isTempRootEmpty()).as("分析完成后临时目录必须为空").isTrue();
    }

    @Test
    @DisplayName("非法 URL 直接 400，不产生任务")
    void invalidUrlIsRejectedImmediately() throws Exception {
        HttpResponse<String> response = post("/api/repos", "{\"url\": \"ftp://example.com/x\"}");

        assertThat(response.statusCode()).isEqualTo(400);
        JsonNode body = JsonMapper.builder().build().readTree(response.body());
        assertThat(body.get("error").asString()).isNotBlank();
    }

    // ---------- helpers ----------

    private JsonNode pollStatus(String taskId, Duration limit) throws Exception {
        long deadline = System.nanoTime() + limit.toNanos();
        while (System.nanoTime() < deadline) {
            HttpResponse<String> response = get("/api/repos/" + taskId + "/status");
            assertThat(response.statusCode()).isEqualTo(200);
            JsonNode body = JsonMapper.builder().build().readTree(response.body());
            String status = body.get("status").asString();
            if (status.equals("done") || status.equals("failed")) {
                return body;
            }
            Thread.sleep(500);
        }
        throw new AssertionError("任务未在期限内结束");
    }

    private HttpResponse<String> get(String path) throws Exception {
        HttpRequest request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
                .header("Accept", "application/json")
                .GET()
                .build();
        return httpClient.send(request, HttpResponse.BodyHandlers.ofString());
    }

    private HttpResponse<String> post(String path, String body) throws Exception {
        HttpRequest request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
                .header("Content-Type", "application/json")
                .header("Accept", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build();
        return httpClient.send(request, HttpResponse.BodyHandlers.ofString());
    }

    private boolean isTempRootEmpty() {
        Path tempRoot = Path.of(System.getProperty("java.io.tmpdir"), "codecompass");
        if (!Files.isDirectory(tempRoot)) {
            return true;
        }
        try (var entries = Files.list(tempRoot)) {
            return entries.findAny().isEmpty();
        } catch (java.io.IOException e) {
            throw new IllegalStateException(e);
        }
    }
}
