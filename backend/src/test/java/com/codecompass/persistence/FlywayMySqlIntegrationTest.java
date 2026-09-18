package com.codecompass.persistence;

import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.ResultSet;
import java.util.ArrayList;
import java.util.List;

import javax.sql.DataSource;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import com.codecompass.service.AnswerResponse;
import com.codecompass.service.CacheKey;
import com.codecompass.service.CacheService;
import com.codecompass.service.MysqlCacheService;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * T13 集成闸门：同一套 Flyway 脚本在**真 MySQL 8** 上迁移成功，且默认
 * CacheService Bean 是 MysqlCacheService、写读回环可用。
 *
 * <p>前置：库已建（backend/db/init-db.sql）；环境变量
 * {@code DB_URL / DB_USER / DB_PASSWORD} 指向 MySQL。未设置时自动跳过
 * （单元测试的 H2 闸门由 {@link FlywayMigrationTest} 承担）。需要网络/真实数据库，@Tag("integration")。
 */
@Tag("integration")
@EnabledIfEnvironmentVariable(named = "DB_URL", matches = ".+")
@SpringBootTest
class FlywayMySqlIntegrationTest {

    private static final List<String> EXPECTED_TABLES = List.of(
            "anonymous_users", "learning_paths", "quizzes", "share_snapshots",
            "notes", "progress", "achievements", "user_actions", "cache_entries");

    @Autowired
    private DataSource dataSource;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private CacheService cacheService;

    @Test
    @DisplayName("真 MySQL：Flyway 迁移出全部 9 张首批表")
    void baselineTablesAreMigratedOnMySql() throws Exception {
        List<String> tables = new ArrayList<>();
        try (Connection connection = dataSource.getConnection()) {
            DatabaseMetaData metadata = connection.getMetaData();
            try (ResultSet resultSet = metadata.getTables(connection.getCatalog(), null, "%", new String[]{"TABLE"})) {
                while (resultSet.next()) {
                    tables.add(resultSet.getString("TABLE_NAME").toLowerCase());
                }
            }
        }

        assertThat(tables)
                .as("真 MySQL 上应迁移出全部首批表")
                .containsAll(EXPECTED_TABLES);
    }

    @Test
    @DisplayName("默认 CacheService Bean 是 MysqlCacheService，写读回环 + 直查行数")
    void defaultCacheBeanPersistsOnMySql() {
        assertThat(cacheService).isInstanceOf(MysqlCacheService.class);

        CacheKey key = new CacheKey("itest-sha", "A.java", "itest-q", "gpt-4o-mini");
        cacheService.put(key, new AnswerResponse("集成回答", List.of(), "gpt-4o-mini"));

        assertThat(cacheService.get(key)).contains(new AnswerResponse("集成回答", List.of(), "gpt-4o-mini"));
        Long rows = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM cache_entries", Long.class);
        assertThat(rows).as("写入后 cache_entries 至少一行").isPositive();
    }
}
