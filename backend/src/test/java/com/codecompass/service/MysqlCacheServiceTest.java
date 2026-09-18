package com.codecompass.service;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import com.codecompass.testutil.MutableClock;

import tools.jackson.databind.json.JsonMapper;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * T13 的 MySQL 缓存实现（跑在 H2 MODE=MySQL 上，同一套 DDL）。
 *
 * <p>条目计数按用户要求用 JdbcTemplate 直查 cache_entries —— 不依赖被测类的
 * 任何方法，计数断言才独立。
 */
class MysqlCacheServiceTest {

    private static final Instant T0 = Instant.parse("2026-09-18T00:00:00Z");

    private JdbcTemplate jdbcTemplate;
    private MysqlCacheService cache;
    private MutableClock clock;

    @BeforeEach
    void setUp() {
        clock = new MutableClock(T0);
        jdbcTemplate = new JdbcTemplate(new DriverManagerDataSource(
                "jdbc:h2:mem:mysql-cache-test;MODE=MySQL;DB_CLOSE_DELAY=-1", "sa", ""));
        jdbcTemplate.execute("""
                CREATE TABLE IF NOT EXISTS cache_entries (
                  cache_key VARCHAR(64) PRIMARY KEY,
                  value_json TEXT NOT NULL,
                  expires_at DATETIME NOT NULL,
                  created_at DATETIME NOT NULL
                )""");
        jdbcTemplate.execute("DELETE FROM cache_entries");
        cache = new MysqlCacheService(
                jdbcTemplate, JsonMapper.builder().build(), clock, new CacheProperties());
    }

    private static CacheKey key(String sha, String file, String question) {
        return new CacheKey(sha, file, question, "gpt-4o-mini");
    }

    private static AnswerResponse answer(String text) {
        return new AnswerResponse(text, List.of(), "gpt-4o-mini");
    }

    private long rowsInTable() {
        Long count = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM cache_entries", Long.class);
        return count == null ? 0 : count;
    }

    @Test
    @DisplayName("put 后 get 命中同一值，表内恰有一行")
    void putThenGetReturnsSameValue() {
        cache.put(key("sha-1", "A.java", "q1"), answer("回答 1"));

        assertThat(cache.get(key("sha-1", "A.java", "q1"))).contains(answer("回答 1"));
        assertThat(rowsInTable()).isEqualTo(1);
    }

    @Test
    @DisplayName("未知 key 返回 empty，不产生行")
    void getUnknownKeyReturnsEmpty() {
        assertThat(cache.get(key("sha-1", "A.java", "nope"))).isEmpty();
        assertThat(rowsInTable()).isZero();
    }

    @Test
    @DisplayName("过期条目在 get 时被剔除（惰性清理），表内行数归零")
    void expiredEntryIsRemovedOnGet() {
        cache.put(key("sha-1", "A.java", "q1"), answer("回答 1"));
        clock.advance(Duration.ofHours(2));

        assertThat(cache.get(key("sha-1", "A.java", "q1"))).isEmpty();
        assertThat(rowsInTable()).as("过期行应在读取时被删除").isZero();
    }

    @Test
    @DisplayName("同 key 重复 put 是 upsert：值更新、不产生第二行")
    void putOverwritesExistingKey() {
        cache.put(key("sha-1", "A.java", "q1"), answer("旧回答"));
        cache.put(key("sha-1", "A.java", "q1"), answer("新回答"));

        assertThat(cache.get(key("sha-1", "A.java", "q1"))).contains(answer("新回答"));
        assertThat(rowsInTable()).isEqualTo(1);
    }

    @Test
    @DisplayName("size() 只数未过期行；过期行仍在表内（惰性）")
    void sizeCountsNonExpiredRowsOnly() {
        cache.put(key("sha-1", "A.java", "q1"), answer("1"));
        cache.put(key("sha-1", "B.java", "q2"), answer("2"));
        assertThat(cache.size()).isEqualTo(2);

        clock.advance(Duration.ofHours(2));
        assertThat(cache.size()).isZero();
        assertThat(rowsInTable()).as("过期行靠惰性清理，未被访问前仍在表内").isEqualTo(2);
    }
}
