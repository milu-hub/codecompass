package com.codecompass.web;

import com.codecompass.service.LlmConfig;

import jakarta.servlet.http.HttpServletRequest;

/**
 * 从请求头解析用户自填的 LLM 配置（{@code X-LLM-Api-Key} / {@code X-LLM-Base-Url} /
 * {@code X-LLM-Model}），{@code /ask} 与 {@code /api/repos/{id}/quiz} 共用。
 *
 * <p>三个头都没有 key 时返回 {@code null}（调用方回落服务端默认）；base-url / model
 * 缺省时用服务端默认补齐。
 *
 * <p><b>安全</b>：key 只在此构造临时 {@link LlmConfig}，用完即弃，绝不缓存、绝不落库、
 * 绝不记日志。这里故意抽成单一来源，避免两处控制器各自复制这段安全敏感逻辑。
 */
public final class LlmRequestConfigResolver {

    private LlmRequestConfigResolver() {
    }

    public static LlmConfig resolve(HttpServletRequest request, LlmConfig serverDefault) {
        String apiKey = request.getHeader("X-LLM-Api-Key");
        if (apiKey == null || apiKey.isBlank()) {
            return null;
        }
        String baseUrl = firstNonBlank(request.getHeader("X-LLM-Base-Url"), serverDefault.baseUrl());
        String model = firstNonBlank(request.getHeader("X-LLM-Model"), serverDefault.model());
        return new LlmConfig("request", "custom", baseUrl, apiKey.trim(), model, false);
    }

    private static String firstNonBlank(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value;
    }
}
