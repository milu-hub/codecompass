package com.codecompass.web;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Instant;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * T0 验收：后端能启动，且 /health 可用。
 *
 * 刻意用真实端口（RANDOM_PORT）而不是 MockMvc 切片测试：
 * T0 的验收标准是"后端能启动"，只有真正绑定端口、走完一次 HTTP 往返才算证明。
 * 另外 Spring Boot 4 已把 @WebMvcTest 切片从 spring-boot-starter-test 移出
 * （迁到 spring-boot-webmvc-test），本项目 T0 不引该额外依赖。
 *
 * 断言一律针对单个字段，不比对整串 JSON —— Jackson 3 默认按字段名字母序输出，
 * 整串比对会因为字段顺序变化而假失败。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class HealthControllerTest {

    @LocalServerPort
    private int port;

    private final HttpClient httpClient = HttpClient.newHttpClient();

    @Test
    @DisplayName("GET /health 返回 200、JSON 内容类型，且 status 为 UP")
    void healthReturnsUpStatus() throws Exception {
        HttpResponse<String> response = get("/health");

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.headers().firstValue("Content-Type").orElse(""))
                .contains("application/json");
        assertThat(json(response).get("status").asString()).isEqualTo("UP");
    }

    @Test
    @DisplayName("GET /health 回显服务名与版本")
    void healthReportsServiceIdentity() throws Exception {
        JsonNode body = json(get("/health"));

        assertThat(body.get("service").asString()).isEqualTo("codecompass-backend");
        assertThat(body.get("version").asString()).isEqualTo("0.0.1-SNAPSHOT");
    }

    @Test
    @DisplayName("GET /health 的 time 是 ISO-8601 UTC 字符串，不是时间戳数字")
    void healthReturnsIso8601Timestamp() throws Exception {
        JsonNode time = json(get("/health")).get("time");

        assertThat(time.isString())
                .as("time 必须是字符串；若为数字说明 Jackson 3 仍在按时间戳序列化日期")
                .isTrue();
        assertThat(time.asString())
                .matches("\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2}(\\.\\d+)?Z");
        assertThat(Instant.parse(time.asString())).isBefore(Instant.now().plusSeconds(60));
    }

    private HttpResponse<String> get(String path) throws Exception {
        HttpRequest request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
                .header("Accept", "application/json")
                .GET()
                .build();
        return httpClient.send(request, HttpResponse.BodyHandlers.ofString());
    }

    private JsonNode json(HttpResponse<String> response) {
        return JsonMapper.builder().build().readTree(response.body());
    }
}
