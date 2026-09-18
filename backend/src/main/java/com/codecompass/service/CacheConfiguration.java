package com.codecompass.service;

import java.time.Clock;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * T11 装配。两个 Bean 都按接口暴露 —— 业务层（AnswerService）不依赖内存实现类。
 * 注入 Clock 让 TTL 与「跨天翻转」可用假钟测试。
 */
@Configuration
@EnableConfigurationProperties({CacheProperties.class, RateLimitProperties.class})
public class CacheConfiguration {

    @Bean
    public Clock clock() {
        return Clock.systemDefaultZone();
    }

    @Bean
    public CacheService cacheService(Clock clock, CacheProperties properties) {
        return new InMemoryCacheService(clock, properties);
    }

    @Bean
    public RateLimiter rateLimiter(Clock clock, RateLimitProperties properties) {
        return new InMemoryRateLimiter(clock, properties);
    }
}
