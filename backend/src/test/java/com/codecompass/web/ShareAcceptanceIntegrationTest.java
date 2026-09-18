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
 * F6 端到端验收：真实 petclinic → 写笔记 → 生成分享快照 → 无 Cookie 打开短链页，
 * 页面含依赖图与**分享者本人的笔记**，且不含敏感信息。需要网络（克隆），@Tag("integration")。
 */
@Tag("integration")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class ShareAcceptanceIntegrationTest {

    private static final String PETCLINIC = "https://github.com/spring-projects/spring-petclinic";

    @LocalServerPort
    private int port;

    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .build();

    @Test
    @DisplayName("真实 petclinic：短链在无 Cookie 浏览器打开，含依赖图与本人笔记，无敏感信息")
    void shareAcceptance() throws Exception {
        JsonMapper mapper = JsonMapper.builder().build();

        // 先建立匿名身份（后续写笔记与生成分享必须是同一个 clientId）
        HttpResponse<String> me = get("/api/me", null);
        assertThat(me.statusCode()).isEqualTo(200);
        String cookie = me.headers().firstValue("Set-Cookie").orElseThrow().split(";")[0];

        HttpResponse<String> submit = post("/api/repos", "{\"url\": \"" + PETCLINIC + "\"}", cookie);
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

        // 写一条本人笔记 —— 分享页必须带上它（笔记没有别的"另存"途径）
        String noteContent = "分享页笔记验证：入口类先读";
        assertThat(post("/api/notes", "{\"repoUrl\":\"" + PETCLINIC
                + "\",\"codeUnitId\":\"repo:unit-1\",\"content\":\"" + noteContent + "\"}", cookie)
                .statusCode()).isEqualTo(200);

        HttpResponse<String> share = post("/api/repos/" + taskId + "/share", null, cookie);
        assertThat(share.statusCode()).isEqualTo(200);
        String sharePath = mapper.readTree(share.body()).path("url").asText();
        assertThat(sharePath).startsWith("/share/");

        // 无 Cookie 打开
        HttpResponse<String> page = get(sharePath, null);
        assertThat(page.statusCode()).isEqualTo(200);
        String html = page.body();
        assertThat(html).contains("由 CodeCompass 生成").contains("mermaid");
        assertThat(html).contains("笔记").contains(noteContent);
        // 脱敏：不得出现完整源码片段与任何 API key 字样
        assertThat(html).doesNotContain("class OwnerController {").doesNotContain("sk-");
    }

    private HttpResponse<String> get(String path, String cookie) throws Exception {
        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
                .header("Accept", "text/html, application/json");
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
        return httpClient.send(builder.POST(HttpRequest.BodyPublishers.ofString(body == null ? "" : body)).build(),
                HttpResponse.BodyHandlers.ofString());
    }
}
