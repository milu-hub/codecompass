package com.codecompass.service;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/**
 * 内存缓存：{@code LinkedHashMap} 访问序 LRU + 容量上限，条目带时间戳做 TTL。
 *
 * <p>TTL 只在 get/put 时惰性判定 —— 过期条目不主动清理（MVP 无调度器），
 * 但容量上限保证内存有硬顶；put 时顺手批量剔除已过期条目，防止僵尸条目占满容量。
 */
public class InMemoryCacheService implements CacheService {

    private final Clock clock;
    private final Duration ttl;
    private final Map<CacheKey, Entry> entries;

    public InMemoryCacheService(Clock clock, CacheProperties properties) {
        this.clock = clock;
        this.ttl = properties.getTtl();
        int maxSize = Math.max(1, properties.getMaxSize());
        this.entries = Collections.synchronizedMap(new LinkedHashMap<>(16, 0.75f, true) {
            @Override
            protected boolean removeEldestEntry(Map.Entry<CacheKey, Entry> eldest) {
                return size() > maxSize;
            }
        });
    }

    @Override
    public Optional<AnswerResponse> get(CacheKey key) {
        synchronized (entries) {
            Entry entry = entries.get(key);
            if (entry == null) {
                return Optional.empty();
            }
            if (entry.isExpired(clock.instant(), ttl)) {
                entries.remove(key);
                return Optional.empty();
            }
            return Optional.of(entry.value());
        }
    }

    @Override
    public void put(CacheKey key, AnswerResponse value) {
        synchronized (entries) {
            entries.entrySet().removeIf(e -> e.getValue().isExpired(clock.instant(), ttl));
            entries.put(key, new Entry(value, clock.instant()));
        }
    }

    @Override
    public long size() {
        synchronized (entries) {
            return entries.size();
        }
    }

    private record Entry(AnswerResponse value, Instant createdAt) {
        boolean isExpired(Instant now, Duration ttl) {
            return !now.isBefore(createdAt.plus(ttl));
        }
    }
}
