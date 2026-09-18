package com.codecompass.service;

import java.time.Clock;
import java.time.LocalDate;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 内存限流：每身份每自然日一个原子计数器。
 *
 * <p>语义钉死：
 * <ul>
 *   <li><b>先加后判</b>：{@code addAndGet} 之后比较上限 —— 并发下可能微超，不回滚
 *       （超出的部分如实计入，便于诊断是谁在超用）；</li>
 *   <li><b>跨天自然翻转</b>：窗口 key 含 {@link LocalDate}，午夜后新日期自动从零开始，
 *       旧日期条目在每次 consume 时惰性清理，不无限增长；</li>
 *   <li><b>上限实时读取</b>：配置改动无需重建实例（也便于测试收紧额度）。</li>
 * </ul>
 */
public class InMemoryRateLimiter implements RateLimiter {

    private final Clock clock;
    private final RateLimitProperties properties;
    private final Map<String, Map<LocalDate, AtomicLong>> perClient = new ConcurrentHashMap<>();

    public InMemoryRateLimiter(Clock clock, RateLimitProperties properties) {
        this.clock = clock;
        this.properties = properties;
    }

    @Override
    public Consumption consume(String clientKey, long estimatedTokens) {
        LocalDate today = LocalDate.now(clock);
        long dailyLimit = Math.max(1, properties.getDailyTokenLimit());
        Map<LocalDate, AtomicLong> byDate =
                perClient.computeIfAbsent(clientKey, key -> new ConcurrentHashMap<>());
        byDate.keySet().removeIf(date -> !date.equals(today));

        AtomicLong counter = byDate.computeIfAbsent(today, date -> new AtomicLong());
        long used = counter.addAndGet(Math.max(0, estimatedTokens));
        return new Consumption(used <= dailyLimit, used, dailyLimit);
    }
}
