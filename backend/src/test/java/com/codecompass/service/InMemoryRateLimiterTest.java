package com.codecompass.service;

import java.time.Duration;
import java.time.Instant;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.codecompass.testutil.MutableClock;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 内存限流：每日额度、跨天重置、客户端隔离、负数容错。
 *
 * 用假钟拨到「第二天」验证窗口翻转 —— 等真实午夜不可行。
 */
class InMemoryRateLimiterTest {

    private static final Instant T0 = Instant.parse("2026-09-18T08:00:00Z");

    private static RateLimitProperties properties(long limit) {
        RateLimitProperties properties = new RateLimitProperties();
        properties.setDailyTokenLimit(limit);
        return properties;
    }

    @Test
    @DisplayName("额度内消耗：放行并累计 usedToday")
    void allowsWithinLimit() {
        InMemoryRateLimiter limiter = new InMemoryRateLimiter(new MutableClock(T0), properties(100));

        RateLimiter.Consumption consumption = limiter.consume("client-1", 60);

        assertThat(consumption.allowed()).isTrue();
        assertThat(consumption.usedToday()).isEqualTo(60);
        assertThat(consumption.dailyLimit()).isEqualTo(100);
    }

    @Test
    @DisplayName("超过每日上限：拒绝且不回滚（超出的部分如实累计）")
    void rejectsBeyondLimit() {
        InMemoryRateLimiter limiter = new InMemoryRateLimiter(new MutableClock(T0), properties(100));

        limiter.consume("client-1", 60);
        RateLimiter.Consumption second = limiter.consume("client-1", 50);

        assertThat(second.allowed()).isFalse();
        assertThat(second.usedToday()).isEqualTo(110);
    }

    @Test
    @DisplayName("跨天窗口重置：昨天消耗不带入今天")
    void resetsNextDay() {
        MutableClock clock = new MutableClock(T0);
        InMemoryRateLimiter limiter = new InMemoryRateLimiter(clock, properties(100));

        limiter.consume("client-1", 90);
        clock.advance(Duration.ofDays(1));

        RateLimiter.Consumption nextDay = limiter.consume("client-1", 40);

        assertThat(nextDay.allowed()).isTrue();
        assertThat(nextDay.usedToday()).isEqualTo(40);
    }

    @Test
    @DisplayName("不同客户端各自独立计数")
    void separateClientsHaveSeparateCounters() {
        InMemoryRateLimiter limiter = new InMemoryRateLimiter(new MutableClock(T0), properties(100));

        assertThat(limiter.consume("client-a", 90).allowed()).isTrue();
        assertThat(limiter.consume("client-b", 90).allowed()).isTrue();
    }

    @Test
    @DisplayName("负数消耗按 0 计（调用方估算失误不该拿到负额度）")
    void negativeTokensCountAsZero() {
        InMemoryRateLimiter limiter = new InMemoryRateLimiter(new MutableClock(T0), properties(100));

        RateLimiter.Consumption consumption = limiter.consume("client-1", -5);

        assertThat(consumption.allowed()).isTrue();
        assertThat(consumption.usedToday()).isZero();
    }
}
