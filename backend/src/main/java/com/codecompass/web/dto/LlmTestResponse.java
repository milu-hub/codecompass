package com.codecompass.web.dto;

/**
 * 「测试连接」结果。无论连通与否都返回本结构（HTTP 200），前端按 {@code ok} 展示。
 * {@code message} 保证不含 apiKey。
 */
public record LlmTestResponse(boolean ok, String message) {
}
