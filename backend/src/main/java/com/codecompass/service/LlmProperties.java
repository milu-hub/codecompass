package com.codecompass.service;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * T10 LLM 配置。api-key 留空 = 未配置，/ask 返回 502 而非 500。
 * 端点走 OpenAI 兼容协议，换 provider 只改配置不改代码。
 */
@ConfigurationProperties(prefix = "codecompass.llm")
public class LlmProperties {

    /** 元数据标签（openai / deepseek / …）。MVP 不参与路由，仅随配置模型透出。 */
    private String provider = "openai";

    private String baseUrl = "https://api.openai.com/v1";

    private String apiKey = "";

    private String model = "gpt-4o-mini";

    /** 单次 HTTP 调用的连接/读取超时 —— 重试预算外的第二道成本护栏。 */
    private Duration timeout = Duration.ofSeconds(60);

    /** 引用校验失败时的重试次数上限（含首次，共 maxAttempts 次调用）。 */
    private int maxAttempts = 3;

    public String getProvider() {
        return provider;
    }

    public void setProvider(String provider) {
        this.provider = provider;
    }

    public String getBaseUrl() {
        return baseUrl;
    }

    public void setBaseUrl(String baseUrl) {
        this.baseUrl = baseUrl;
    }

    public String getApiKey() {
        return apiKey;
    }

    public void setApiKey(String apiKey) {
        this.apiKey = apiKey;
    }

    public String getModel() {
        return model;
    }

    public void setModel(String model) {
        this.model = model;
    }

    public Duration getTimeout() {
        return timeout;
    }

    public void setTimeout(Duration timeout) {
        this.timeout = timeout;
    }

    public int getMaxAttempts() {
        return maxAttempts;
    }

    public void setMaxAttempts(int maxAttempts) {
        this.maxAttempts = maxAttempts;
    }

    public boolean configured() {
        return apiKey != null && !apiKey.isBlank()
                && baseUrl != null && !baseUrl.isBlank();
    }
}
