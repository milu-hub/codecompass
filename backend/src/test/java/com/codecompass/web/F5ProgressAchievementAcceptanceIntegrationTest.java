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
 * F5 进度+成就端到端验收：真实 petclinic 分析 → FIRST_REPO 解锁；进度持久化；
 * 笔记 → FIRST_NOTE 解锁。不需要 LLM，@Tag("integration")。
 */
@Tag("integration")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class F5ProgressAchievementAcceptanceIntegrationTest {

    private static final String PETCLINIC = "https://github.com/spring-projects/spring-petclinic";

    @LocalServerPort
    private int port;

    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .build();

    @Test
    @DisplayName("分析成功解锁 FIRST_REPO；进度持久化；笔记解锁 FIRST_NOTE")
    void progressAndAchievementAcceptance() throws Exception {
        JsonMapper mapper = JsonMapper.builder().build();

        // 身份 + Cookie
        HttpResponse<String> me = get("/api/me", null);
        assertThat(me.statusCode()).isEqualTo(200);
        String cookie = me.headers().firstValue("Set-Cookie").orElseThrow().split(";")[0];
        String clientId = mapper.readTree(me.body()).path("clientId").asText();
        assertThat(clientId).isNotBlank();

        // 分析 + 轮询（status 首次观察到 done 时记录 analyze → FIRST_REPO）
        HttpResponse<String> submit = post("/api/repos",
                "{\"url\": \"" + PETCLINIC + "\"}", cookie);
        assertThat(submit.statusCode()).isEqualTo(201);
        String taskId = mapper.readTree(submit.body()).get("taskId").asString();
        long deadline = System.nanoTime() + Duration.ofSeconds(120).toNanos();
        String state = "";
        while (System.nanoTime() < deadline) {
            HttpResponse<String> status = get("/api/repos/" + taskId + "/status", cookie);
            state = mapper.readTree(status.body()).get("status").asString();
            if (state.equals("done") || state.equals("failed")) {
                break;
            }
            Thread.sleep(500);
        }
        assertThat(state).isEqualTo("done");

        JsonNode achievements = mapper.readTree(get("/api/achievements", cookie).body());
        JsonNode firstRepo = find(achievements, "FIRST_REPO");
        assertThat(firstRepo.path("unlockedAt").asText())
                .as("完成第一个分析后 FIRST_REPO 必须解锁").isNotBlank();

        // 进度持久化
        HttpResponse<String> putProgress = put("/api/progress",
                "{\"repoUrl\":\"" + PETCLINIC + "\",\"codeUnitId\":\"repo:u\",\"status\":\"done\"}", cookie);
        assertThat(putProgress.statusCode()).isEqualTo(200);
        HttpResponse<String> listProgress = get("/api/progress?repoUrl="
                + java.net.URLEncoder.encode(PETCLINIC, "UTF-8"), cookie);
        assertThat(mapper.readTree(listProgress.body()).size()).isEqualTo(1);

        // 笔记 → FIRST_NOTE
        HttpResponse<String> note = post("/api/notes",
                "{\"repoUrl\":\"" + PETCLINIC + "\",\"codeUnitId\":\"repo:u\",\"content\":\"入口类先读\"}", cookie);
        assertThat(note.statusCode()).isEqualTo(200);
        JsonNode after = mapper.readTree(get("/api/achievements", cookie).body());
        assertThat(find(after, "FIRST_NOTE").path("unlockedAt").asText()).isNotBlank();
    }

    private static JsonNode find(JsonNode array, String code) {
        for (JsonNode node : array) {
            if (node.path("code").asText().equals(code)) {
                return node;
            }
        }
        throw new AssertionError("成就不存在：" + code);
    }

    private HttpResponse<String> get(String path, String cookie) throws Exception {
        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
                .header("Accept", "application/json");
        if (cookie != null) {
            builder.header("Cookie", cookie);
        }
        return httpClient.send(builder.GET().build(), HttpResponse.BodyHandlers.ofString());
    }

    private HttpResponse<String> post(String path, String body, String cookie) throws Exception {
        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
                .header("Content-Type", "application/json").header("Accept", "application/json");
        if (cookie != null) {
            builder.header("Cookie", cookie);
        }
        return httpClient.send(builder.POST(HttpRequest.BodyPublishers.ofString(body)).build(),
                HttpResponse.BodyHandlers.ofString());
    }

    private HttpResponse<String> put(String path, String body, String cookie) throws Exception {
        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
                .header("Content-Type", "application/json").header("Accept", "application/json")
                .header("Cookie", cookie);
        return httpClient.send(builder.PUT(HttpRequest.BodyPublishers.ofString(body)).build(),
                HttpResponse.BodyHandlers.ofString());
    }
}
