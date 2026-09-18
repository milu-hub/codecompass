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
 * F5 端到端验收：Cookie 恢复身份 + 昵称 + 笔记持久化与隔离。不需要 LLM，@Tag("integration")。
 */
@Tag("integration")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class IdentityAcceptanceIntegrationTest {

    @LocalServerPort
    private int port;

    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .build();

    @Test
    @DisplayName("Cookie 身份恢复 + 昵称 + 笔记持久化")
    void identityAndNotesAcceptance() throws Exception {
        JsonMapper mapper = JsonMapper.builder().build();

        // 1. 首次访问 → 生成身份 + Set-Cookie
        HttpResponse<String> first = get("/api/me", null);
        assertThat(first.statusCode()).isEqualTo(200);
        String setCookie = first.headers().firstValue("Set-Cookie").orElse("");
        assertThat(setCookie).contains("cc_client_id=").contains("HttpOnly");
        String clientId = mapper.readTree(first.body()).path("clientId").asText();
        assertThat(clientId).isNotBlank();
        String cookie = setCookie.split(";")[0];

        // 2. 带 Cookie 再次访问 → 同一身份
        HttpResponse<String> second = get("/api/me", cookie);
        assertThat(mapper.readTree(second.body()).path("clientId").asText()).isEqualTo(clientId);

        // 3. 改昵称
        HttpResponse<String> nick = put("/api/me", "{\"nickname\":\"学习者\"}", cookie);
        assertThat(nick.statusCode()).isEqualTo(200);
        assertThat(mapper.readTree(nick.body()).path("nickname").asText()).isEqualTo("学习者");

        // 4. 写笔记 → 读回（持久化 + 隔离）
        HttpResponse<String> created = post("/api/notes",
                "{\"repoUrl\":\"https://github.com/a/b\",\"codeUnitId\":\"u1\",\"content\":\"入口类先读\"}", cookie);
        assertThat(created.statusCode()).isEqualTo(200);
        HttpResponse<String> list = get("/api/notes?repoUrl=https%3A%2F%2Fgithub.com%2Fa%2Fb", cookie);
        assertThat(list.statusCode()).isEqualTo(200);
        JsonNode notes = mapper.readTree(list.body());
        assertThat(notes.size()).isEqualTo(1);
        assertThat(notes.get(0).path("content").asText()).isEqualTo("入口类先读");
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
                .header("Content-Type", "application/json").header("Accept", "application/json")
                .header("Cookie", cookie);
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
