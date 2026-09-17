package com.codecompass.service;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestClient;

import com.sun.net.httpserver.HttpServer;

import tools.jackson.databind.json.JsonMapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * OpenAI 兼容客户端的真实 HTTP 契约（本地 HttpServer，不碰外网）：
 * 请求体形状、Bearer 头、choices[0].message.content 提取、非 2xx 报错、未配置拒绝。
 */
class OpenAiCompatibleLlmClientTest {

    private HttpServer server;
    private String baseUrl;
    private final AtomicReference<String> capturedBody = new AtomicReference<>();
    private final AtomicReference<String> capturedAuth = new AtomicReference<>();
    private final AtomicReference<String> scriptedResponse = new AtomicReference<>("{}");
    private final AtomicInteger scriptedStatus = new AtomicInteger(200);

    @BeforeEach
    void setUp() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/chat/completions", exchange -> {
            capturedBody.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            capturedAuth.set(exchange.getRequestHeaders().getFirst("Authorization"));
            byte[] bytes = scriptedResponse.get().getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(scriptedStatus.get(), bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
        });
        server.start();
        baseUrl = "http://127.0.0.1:" + server.getAddress().getPort() + "/v1";
    }

    @AfterEach
    void tearDown() {
        server.stop(0);
    }

    private OpenAiCompatibleLlmClient configuredClient() {
        LlmProperties properties = new LlmProperties();
        properties.setBaseUrl(baseUrl);
        properties.setApiKey("sk-test-123");
        properties.setModel("deepseek-chat");
        return new OpenAiCompatibleLlmClient(
                JsonMapper.builder().build(), RestClient.builder().build(), properties);
    }

    @Test
    @DisplayName("未配置 api-key：立刻报错，不发任何网络请求")
    void notConfiguredThrows() {
        LlmProperties properties = new LlmProperties();
        properties.setBaseUrl(baseUrl);

        assertThatThrownBy(() -> new OpenAiCompatibleLlmClient(
                JsonMapper.builder().build(), RestClient.builder().build(), properties)
                .complete("system", "user"))
                .isInstanceOf(LlmException.class)
                .hasMessageContaining("未配置");
    }

    @Test
    @DisplayName("请求体是 OpenAI chat/completions 形状，返回 choices[0].message.content")
    void sendsOpenAiCompatibleRequestAndExtractsContent() {
        scriptedResponse.set("{\"choices\":[{\"message\":{\"content\":\"答案是 42\"}}]}");

        String content = configuredClient().complete("你是助手", "什么是 OwnerController");

        assertThat(content).isEqualTo("答案是 42");
        assertThat(capturedAuth.get()).isEqualTo("Bearer sk-test-123");
        assertThat(capturedBody.get())
                .contains("\"model\":\"deepseek-chat\"")
                .contains("\"role\":\"system\"")
                .contains("\"role\":\"user\"")
                .contains("什么是 OwnerController");
    }

    @Test
    @DisplayName("非 2xx 响应：包装成 LlmException 并带状态码")
    void serverErrorThrowsLlmException() {
        scriptedStatus.set(500);
        scriptedResponse.set("{\"error\":\"boom\"}");

        assertThatThrownBy(() -> configuredClient().complete("s", "u"))
                .isInstanceOf(LlmException.class)
                .hasMessageContaining("LLM 调用失败")
                .hasMessageContaining("500");
    }
}
