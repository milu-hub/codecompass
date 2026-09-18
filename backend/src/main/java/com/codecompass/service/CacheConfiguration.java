package com.codecompass.service;

import java.time.Clock;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;

import tools.jackson.databind.json.JsonMapper;

/**
 * T11 装配 + T13 存储切换。
 *
 * <p>{@code CacheService} 接口不变（T11 定死），Bean 按 {@code codecompass.cache.storage}
 * 选择实现：默认 {@code mysql}（F2/F6 阶段切到 MySQL 落点），{@code memory} 保留内存版。
 * F1/F3 业务代码零改动 —— 装配层换 Bean 即完成切换。
 */
@Configuration
@EnableConfigurationProperties({CacheProperties.class, RateLimitProperties.class})
public class CacheConfiguration {

    @Bean
    public Clock clock() {
        return Clock.systemDefaultZone();
    }

    @Bean
    @ConditionalOnProperty(name = "codecompass.cache.storage", havingValue = "memory")
    public CacheService inMemoryCacheService(Clock clock, CacheProperties properties) {
        return new InMemoryCacheService(clock, properties);
    }

    @Bean
    @ConditionalOnProperty(name = "codecompass.cache.storage", havingValue = "mysql", matchIfMissing = true)
    public CacheService mysqlCacheService(JdbcTemplate jdbcTemplate, JsonMapper jsonMapper,
                                          Clock clock, CacheProperties properties) {
        return new MysqlCacheService(jdbcTemplate, jsonMapper, clock, properties);
    }

    @Bean
    public RateLimiter rateLimiter(Clock clock, RateLimitProperties properties) {
        return new InMemoryRateLimiter(clock, properties);
    }
}
