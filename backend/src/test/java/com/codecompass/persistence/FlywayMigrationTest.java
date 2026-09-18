package com.codecompass.persistence;

import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.ResultSet;
import java.util.ArrayList;
import java.util.List;

import javax.sql.DataSource;

import jakarta.persistence.EntityManagerFactory;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * T13 验收：启动时 Flyway 自动迁移出全部首批表（单元测试跑在 H2 MODE=MySQL 上）。
 *
 * <p>用 JDBC 元数据枚举表名（不用 INFORMATION_SCHEMA 查询）——同一段断言
 * 在 H2 与 MySQL 上都成立。另断言 EntityManagerFactory Bean 存在：
 * 持久化层自动配置真正生效的闸门（MyBatis-Plus 预研曾因 Boot 4 弃用
 * spring.factories 而静默失效，此断言防止同类问题复发）。
 */
@SpringBootTest
class FlywayMigrationTest {

    /** SCHEMA_F2_F6.md 的 8 张表 + T13 追加的 cache_entries。 */
    private static final List<String> EXPECTED_TABLES = List.of(
            "anonymous_users", "learning_paths", "quizzes", "share_snapshots",
            "notes", "progress", "achievements", "user_actions", "cache_entries");

    @Autowired
    private DataSource dataSource;

    @Autowired
    private EntityManagerFactory entityManagerFactory;

    @Test
    @DisplayName("启动即迁移：9 张首批表全部存在")
    void baselineTablesAreMigrated() throws Exception {
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
                .as("Flyway 应在启动时建出全部首批表（H2 MODE=MySQL）")
                .containsAll(EXPECTED_TABLES);
    }

    @Test
    @DisplayName("JPA 自动配置在 Boot 4 下生效（EntityManagerFactory Bean 存在）")
    void jpaAutoConfigurationIsActive() {
        assertThat(entityManagerFactory).isNotNull();
    }
}
