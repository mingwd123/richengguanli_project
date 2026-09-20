package com.dayliane.schedule;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@EnabledIfEnvironmentVariable(named = "DAYLIANE_TEST_MYSQL_URL", matches = ".+")
class MySqlFlywayMigrationPathTests {

    @Test
    void productionMigrationsUpgradeVersion22SchemaThroughLatestVersion() throws Exception {
        String adminUrl = requiredEnvironment("DAYLIANE_TEST_MYSQL_URL");
        String username = requiredEnvironment("DAYLIANE_TEST_MYSQL_USERNAME");
        String password = System.getenv().getOrDefault("DAYLIANE_TEST_MYSQL_PASSWORD", "");
        String database = "dayliane_flyway_" + UUID.randomUUID().toString().replace("-", "");
        String databaseUrl = databaseUrl(adminUrl, database);

        try {
            executeAdmin(adminUrl, username, password,
                    "create database `" + database + "` character set utf8mb4 collate utf8mb4_0900_ai_ci");

            Flyway throughVersion22 = flyway(databaseUrl, username, password, "22");
            assertThat(throughVersion22.migrate().migrationsExecuted).isEqualTo(22);
            assertThat(throughVersion22.info().current().getVersion().getVersion()).isEqualTo("22");

            Flyway throughLatest = flyway(databaseUrl, username, password, null);
            assertThat(throughLatest.migrate().migrationsExecuted).isEqualTo(7);
            assertThat(throughLatest.info().current().getVersion().getVersion()).isEqualTo("29");

            JdbcTemplate jdbc = new JdbcTemplate(new DriverManagerDataSource(databaseUrl, username, password));
            assertThat(columnCount(jdbc, database, "team_task_assignee", "completed_fatigue_weight")).isEqualTo(1);
            assertThat(columnCount(jdbc, database, "schedule", "rrule")).isEqualTo(1);
            assertThat(columnCount(jdbc, database, "user", "subscribe_token")).isEqualTo(1);
            assertThat(columnCount(jdbc, database, "user", "ai_record_enabled")).isEqualTo(1);
            assertThat(columnCount(jdbc, database, "schedule", "progress_tracking_enabled")).isEqualTo(1);
            assertThat(columnCount(jdbc, database, "schedule", "progress_percent")).isEqualTo(1);
            assertThat(columnCount(jdbc, database, "schedule", "progress_completed_date")).isEqualTo(1);
            assertThat(columnCount(jdbc, database, "schedule_progress_daily", "completed_load")).isEqualTo(1);
            assertThat(columnCount(jdbc, database, "ticket", "ticket_no")).isEqualTo(1);
            assertThat(columnCount(jdbc, database, "ticket", "duplicate_of_id")).isEqualTo(1);
            assertThat(columnCount(jdbc, database, "ticket_setting", "enabled")).isEqualTo(1);
            assertThat(columnCount(jdbc, database, "ticket_message", "visibility")).isEqualTo(1);
            assertThat(columnCount(jdbc, database, "ticket_attachment", "storage_key")).isEqualTo(1);
            assertThat(columnCount(jdbc, database, "ticket_idempotency", "idempotency_key")).isEqualTo(1);
            assertThat(jdbc.queryForObject("select enabled from ticket_setting where id=1", Boolean.class)).isFalse();
        } finally {
            executeAdmin(adminUrl, username, password, "drop database if exists `" + database + "`");
        }
    }

    private Flyway flyway(String url, String username, String password, String target) {
        var configuration = Flyway.configure()
                .dataSource(url, username, password)
                .locations("classpath:db/migration");
        if (target != null) configuration.target(target);
        return configuration.load();
    }

    private int columnCount(JdbcTemplate jdbc, String database, String table, String column) {
        Integer count = jdbc.queryForObject(
                "select count(*) from information_schema.columns where table_schema=? and table_name=? and column_name=?",
                Integer.class, database, table, column);
        return count == null ? 0 : count;
    }

    private void executeAdmin(String url, String username, String password, String sql) throws Exception {
        try (Connection connection = DriverManager.getConnection(url, username, password);
             Statement statement = connection.createStatement()) {
            statement.execute(sql);
        }
    }

    private String databaseUrl(String adminUrl, String database) {
        int query = adminUrl.indexOf('?');
        String base = query < 0 ? adminUrl : adminUrl.substring(0, query);
        String suffix = query < 0 ? "" : adminUrl.substring(query);
        return (base.endsWith("/") ? base : base + "/") + database + suffix;
    }

    private String requiredEnvironment(String name) {
        String value = System.getenv(name);
        if (value == null || value.isBlank()) throw new IllegalStateException(name + " is required");
        return value;
    }
}
