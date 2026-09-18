package com.codecompass.service;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.codecompass.testutil.MutableClock;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 内存问答缓存：TTL 过期、容量上限 LRU、惰性清理。
 *
 * 用假钟驱动时间 —— 没有它，TTL 测试只能 sleep 真实秒数，慢且脆。
 */
class InMemoryCacheServiceTest {

    private static final Instant T0 = Instant.parse("2026-09-18T00:00:00Z");

    private static CacheKey key(String file, String question) {
        return new CacheKey("sha-1", file, question, "gpt-4o-mini");
    }

    private static AnswerResponse answer(String text) {
        return new AnswerResponse(text, List.of(), "gpt-4o-mini");
    }

    @Test
    @DisplayName("put 后 get 命中同一值，size 增长")
    void putThenGetReturnsSameValue() {
        MutableClock clock = new MutableClock(T0);
        InMemoryCacheService cache = new InMemoryCacheService(clock, new CacheProperties());
        CacheKey key = key("A.java", "q1");

        cache.put(key, answer("回答 1"));

        assertThat(cache.get(key)).contains(answer("回答 1"));
        assertThat(cache.size()).isEqualTo(1);
    }

    @Test
    @DisplayName("未知 key 返回 empty")
    void getUnknownKeyReturnsEmpty() {
        InMemoryCacheService cache = new InMemoryCacheService(new MutableClock(T0), new CacheProperties());

        assertThat(cache.get(key("A.java", "nope"))).isEmpty();
    }

    @Test
    @DisplayName("超过 TTL 的条目：get 视为未命中并当场剔除")
    void expiredEntryIsMissAndRemoved() {
        MutableClock clock = new MutableClock(T0);
        CacheProperties properties = new CacheProperties();
        properties.setTtl(Duration.ofHours(1));
        InMemoryCacheService cache = new InMemoryCacheService(clock, properties);
        cache.put(key("A.java", "q1"), answer("回答 1"));

        clock.advance(Duration.ofHours(1).plusSeconds(1));

        assertThat(cache.get(key("A.java", "q1"))).isEmpty();
        assertThat(cache.size()).as("过期条目应在读取时被剔除").isZero();
    }

    @Test
    @DisplayName("超过容量上限时剔除最久未用（LRU）")
    void evictsOldestBeyondMaxSize() {
        CacheProperties properties = new CacheProperties();
        properties.setMaxSize(2);
        InMemoryCacheService cache = new InMemoryCacheService(new MutableClock(T0), properties);

        cache.put(key("A.java", "q1"), answer("1"));
        cache.put(key("B.java", "q2"), answer("2"));
        cache.get(key("A.java", "q1"));   // 触摸 A，B 变成最久未用
        cache.put(key("C.java", "q3"), answer("3"));

        assertThat(cache.size()).isEqualTo(2);
        assertThat(cache.get(key("B.java", "q2"))).isEmpty();
        assertThat(cache.get(key("A.java", "q1"))).isPresent();
        assertThat(cache.get(key("C.java", "q3"))).isPresent();
    }

    @Test
    @DisplayName("put 时顺手清掉已过期条目，容量不被僵尸条目占满")
    void putPurgesExpiredEntries() {
        MutableClock clock = new MutableClock(T0);
        CacheProperties properties = new CacheProperties();
        properties.setTtl(Duration.ofHours(1));
        properties.setMaxSize(2);
        InMemoryCacheService cache = new InMemoryCacheService(clock, properties);
        cache.put(key("A.java", "q1"), answer("1"));
        cache.put(key("B.java", "q2"), answer("2"));

        clock.advance(Duration.ofHours(2));
        cache.put(key("C.java", "q3"), answer("3"));

        assertThat(cache.size()).isEqualTo(1);
        assertThat(cache.get(key("C.java", "q3"))).isPresent();
    }
}
