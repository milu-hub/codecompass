package com.codecompass.service;

import java.util.List;
import java.util.Map;

import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;

import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * OpenAI 兼容协议客户端：POST {base-url}/chat/completions，
 * 提取 choices[0].message.content。
 *
 * <p>请求与响应都用注入的自动配置 JsonMapper 处理 —— 不 new ObjectMapper、不引 provider SDK。
 */
public class OpenAiCompatibleLlmClient implements LlmClient {

    private final JsonMapper jsonMapper;
    private final RestClient restClient;
    private final LlmProperties properties;

    public OpenAiCompatibleLlmClient(JsonMapper jsonMapper,
                                     RestClient restClient,
                                     LlmProperties properties) {
        this.jsonMapper = jsonMapper;
        this.restClient = restClient;
        this.properties = properties;
    }

    @Override
    public String complete(String systemPrompt, String userPrompt) {
        if (!properties.configured()) {
            throw new LlmException("LLM 未配置：codecompass.llm.api-key 为空（或 base-url 未设置）");
        }
        String raw;
        try {
            raw = restClient.post()
                    .uri(properties.getBaseUrl() + "/chat/completions")
                    .contentType(MediaType.APPLICATION_JSON)
                    .header(HttpHeaders.AUTHORIZATION, "Bearer " + properties.getApiKey())
                    .body(buildRequestBody(systemPrompt, userPrompt))
                    .retrieve()
                    .body(String.class);
        } catch (RestClientException e) {
            int status = e instanceof RestClientResponseException r
                    ? r.getStatusCode().value() : -1;
            throw new LlmException("LLM 调用失败：HTTP " + status, e);
        }
        return extractContent(raw);
    }

    private String buildRequestBody(String systemPrompt, String userPrompt) {
        try {
            return jsonMapper.writeValueAsString(Map.of(
                    "model", properties.getModel(),
                    "messages", List.of(
                            Map.of("role", "system", "content", systemPrompt),
                            Map.of("role", "user", "content", userPrompt))));
        } catch (JacksonException e) {
            throw new LlmException("LLM 请求构造失败", e);
        }
    }

    private String extractContent(String raw) {
        try {
            JsonNode root = jsonMapper.readTree(raw == null ? "{}" : raw);
            JsonNode content = root.path("choices").path(0).path("message").path("content");
            if (!content.isTextual() || content.asText().isBlank()) {
                throw new LlmException("LLM 返回缺少 choices[0].message.content");
            }
            return content.asText();
        } catch (JacksonException e) {
            throw new LlmException("LLM 响应解析失败", e);
        }
    }
}
