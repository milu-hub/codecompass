package com.codecompass.web.dto;

import java.time.Instant;

/**
 * {@code GET /health} 的响应体。
 *
 * 刻意是普通 record、零 Jackson 注解 —— 靠 Spring Boot 自动配置的 Jackson 3 完成序列化，
 * 因此本类不出现任何 tools.jackson / com.fasterxml.jackson import。
 *
 * {@code time} 用 {@link Instant}：Jackson 3 的 WRITE_DATES_AS_TIMESTAMPS 默认关闭，
 * 会输出 ISO-8601 UTC 字符串（形如 2026-09-17T12:34:56.789Z），前端按 string 处理。
 */
public record HealthResponse(String status, String service, String version, Instant time) {

    public static final String STATUS_UP = "UP";

    public static HealthResponse up(String service, String version) {
        return new HealthResponse(STATUS_UP, service, version, Instant.now());
    }
}
