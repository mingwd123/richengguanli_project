package com.dayliane.schedule;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@ActiveProfiles("test")
class ScheduleMigrationSmokeTests {

    @Autowired
    JdbcTemplate jdbc;

    @Test
    void scheduleRepeatColumnsArePresentAndNullable() {
        List<String> nullable = jdbc.queryForList(
                "select is_nullable from information_schema.columns where table_name='schedule' " +
                        "and column_name in ('rrule','series_id','occurrence_date') order by column_name",
                String.class);
        assertThat(nullable).containsExactly("YES", "YES", "YES");
    }

    @Test
    void scheduleRepeatColumnsHaveExpectedTypes() {
        List<String> types = jdbc.queryForList(
                "select lower(data_type) from information_schema.columns where table_name='schedule' " +
                        "and column_name in ('rrule','series_id','occurrence_date') order by column_name",
                String.class);
        assertThat(types).hasSize(3);
        assertThat(types.get(0)).as("occurrence_date type").contains("date");
        assertThat(types.get(1)).as("rrule type").contains("char");
        assertThat(types.get(2)).as("series_id type").contains("char");
    }

    @Test
    void scheduleSeriesIndexesExist() {
        List<String> indexes = jdbc.queryForList(
                "select distinct index_name from information_schema.indexes where table_name='schedule'",
                String.class);
        assertThat(indexes).contains("idx_schedule_series_id", "uk_schedule_series_occurrence");
    }

    @Test
    void scheduleSeriesExdateTableExistsWithUniqueConstraint() {
        List<String> constraints = jdbc.queryForList(
                "select constraint_name from information_schema.table_constraints " +
                        "where table_name='schedule_series_exdate' and constraint_type='UNIQUE' order by constraint_name",
                String.class);
        assertThat(constraints).contains("uk_series_exdate");
    }

    @Test
    void userSubscribeTokenColumnIsPresentAndNullable() {
        List<String> nullable = jdbc.queryForList(
                "select is_nullable from information_schema.columns where table_name='user' and column_name='subscribe_token'",
                String.class);
        assertThat(nullable).containsExactly("YES");
    }

    @Test
    void userSubscribeTokenUniqueIndexExists() {
        List<String> indexes = jdbc.queryForList(
                "select distinct index_name from information_schema.indexes where table_name='user'",
                String.class);
        assertThat(indexes).contains("uk_user_subscribe_token");
    }

    @Test
    void userAiRecordPreferenceIsPresentAndRequired() {
        Map<String, Object> column = jdbc.queryForMap(
                "select is_nullable,column_default from information_schema.columns " +
                        "where table_name='user' and column_name='ai_record_enabled'");
        assertThat(column.get("is_nullable")).isEqualTo("NO");
        assertThat(String.valueOf(column.get("column_default"))).isIn("TRUE", "true", "1");
    }
}
