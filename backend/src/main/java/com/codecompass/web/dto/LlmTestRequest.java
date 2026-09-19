package com.codecompass.web.dto;

/**
 * 「测试连接」请求体。
 *
 * <p><b>apiKey 是明文敏感字段</b>：只在本次请求内使用，用完即弃 —— 不落库、不记日志、
 * 不回显。用请求体而不是 URL 参数，避免 key 被代理访问日志记录。
 */
public record LlmTestRequest(String baseUrl, String apiKey, String model) {
}
