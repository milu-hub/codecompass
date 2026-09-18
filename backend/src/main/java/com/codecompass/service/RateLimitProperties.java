package com.codecompass.service;

import org.springframework.boot.context.properties.ConfigurationProperties;

/** T11 限流配置：按匿名身份的自然日 token 上限。 */
@ConfigurationProperties(prefix = "codecompass.rate-limit")
public class RateLimitProperties {

    private long dailyTokenLimit = 100_000;

    public long getDailyTokenLimit() {
        return dailyTokenLimit;
    }

    public void setDailyTokenLimit(long dailyTokenLimit) {
        this.dailyTokenLimit = dailyTokenLimit;
    }
}
