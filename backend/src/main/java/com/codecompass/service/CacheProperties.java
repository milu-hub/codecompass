package com.codecompass.service;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;

/** T11 问答缓存配置：TTL + 容量上限（要求 3 两者都给）。 */
@ConfigurationProperties(prefix = "codecompass.cache")
public class CacheProperties {

    private Duration ttl = Duration.ofHours(1);

    private int maxSize = 500;

    public Duration getTtl() {
        return ttl;
    }

    public void setTtl(Duration ttl) {
        this.ttl = ttl;
    }

    public int getMaxSize() {
        return maxSize;
    }

    public void setMaxSize(int maxSize) {
        this.maxSize = maxSize;
    }
}
