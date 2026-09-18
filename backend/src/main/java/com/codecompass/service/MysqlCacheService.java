package com.codecompass.service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
import java.util.Optional;

import org.springframework.jdbc.core.JdbcTemplate;

import tools.jackson.core.JacksonException;
import tools.jackson.databind.json.JsonMapper;

/**
 * T13：{@link CacheService} 的 MySQL 落点（F2/F6 阶段默认实现）。
 *
 * <p>接口是 T11 定死的（get/put/size），本类只实现不扩展：
 * <ul>
 *   <li>key：{@link CacheKey} 四组件 → SHA-256 hex 单列（{@code \u0000} 分隔，杜绝拼接歧义）；</li>
 *   <li>值：注入的自动配置 JsonMapper 序列化 {@link AnswerResponse} 进 {@code value_json}；
 *       （字段名通用 —— 将来 F2/F4 的缓存结果也进这张表）；</li>
 *   <li>TTL：{@code expires_at}，get 命中时惰性删除过期行；<b>容量上限语义延后</b>（T11
 *       接口只要求「TTL 或容量」其一，内存实现负责容量，本实现负责 TTL）；</li>
 *   <li>{@code size()}：只数未过期行（与内存实现的「存活条目」语义对齐）。</li>
 * </ul>
 */
public class MysqlCacheService implements CacheService {

    private final JdbcTemplate jdbcTemplate;
    private final JsonMapper jsonMapper;
    private final Clock clock;
    private final Duration ttl;

    public MysqlCacheService(JdbcTemplate jdbcTemplate, JsonMapper jsonMapper,
                             Clock clock, CacheProperties properties) {
        this.jdbcTemplate = jdbcTemplate;
        this.jsonMapper = jsonMapper;
        this.clock = clock;
        this.ttl = properties.getTtl();
    }

    @Override
    public Optional<AnswerResponse> get(CacheKey key) {
        String hash = hash(key);
        var rows = jdbcTemplate.query(
                "SELECT value_json, expires_at FROM cache_entries WHERE cache_key = ?",
                (rs, rowNum) -> new Stored(rs.getString("value_json"),
                        rs.getTimestamp("expires_at").toInstant()),
                hash);
        if (rows.isEmpty()) {
            return Optional.empty();
        }
        Stored stored = rows.get(0);
        if (!stored.expiresAt().isAfter(clock.instant())) {
            jdbcTemplate.update("DELETE FROM cache_entries WHERE cache_key = ?", hash);
            return Optional.empty();
        }
        try {
            return Optional.of(jsonMapper.readValue(stored.json(), AnswerResponse.class));
        } catch (JacksonException e) {
            // 缓存值解析失败当未命中处理，绝不让缓存层打断问答主流程
            return Optional.empty();
        }
    }

    @Override
    public void put(CacheKey key, AnswerResponse value) {
        try {
            String hash = hash(key);
            String json = jsonMapper.writeValueAsString(value);
            Instant now = clock.instant();
            Instant expiresAt = now.plus(ttl);
            // upsert：VALUES() 写法在 H2 MODE=MySQL 与 MySQL 8 双通（MySQL 8 中已弃用但仍可用）
            jdbcTemplate.update("""
                    INSERT INTO cache_entries (cache_key, value_json, expires_at, created_at)
                    VALUES (?, ?, ?, ?)
                    ON DUPLICATE KEY UPDATE value_json = VALUES(value_json), expires_at = VALUES(expires_at)
                    """, hash, json, Timestamp.from(expiresAt), Timestamp.from(now));
        } catch (JacksonException e) {
            // 序列化失败（理论上不会发生：AnswerResponse 是纯数据）—— 缓存写失败不打断问答
        }
    }

    @Override
    public long size() {
        Long count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM cache_entries WHERE expires_at > ?",
                Long.class, Timestamp.from(clock.instant()));
        return count == null ? 0 : count;
    }

    /** 四组件用 {@code \u0000} 分隔后 SHA-256 —— 任何字段值都不会产生歧义拼接。 */
    private static String hash(CacheKey key) {
        String canonical = key.commitSha() + '\u0000' + key.anchorFile() + '\u0000'
                + key.questionHash() + '\u0000' + key.model();
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(canonical.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 不可用", e);
        }
    }

    private record Stored(String json, Instant expiresAt) {
    }
}
