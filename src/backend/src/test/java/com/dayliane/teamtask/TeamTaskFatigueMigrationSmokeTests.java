package com.dayliane.teamtask;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest
@ActiveProfiles("test")
class TeamTaskFatigueMigrationSmokeTests {

    @Autowired
    JdbcTemplate jdbc;

    @Test
    void completedFatigueColumnsArePresentAndNullable() {
        List<String> nullable = jdbc.queryForList(
                "select is_nullable from information_schema.columns where table_name='team_task_assignee' " +
                        "and column_name in ('completed_fatigue_level','completed_fatigue_weight') order by column_name",
                String.class);
        assertThat(nullable).containsExactly("YES", "YES");
    }

    @Test
    void completedFatigueColumnsHaveExpectedTypes() {
        List<String> types = jdbc.queryForList(
                "select lower(data_type) from information_schema.columns where table_name='team_task_assignee' " +
                        "and column_name in ('completed_fatigue_level','completed_fatigue_weight') order by column_name",
                String.class);
        assertThat(types).hasSize(2);
        assertThat(types.get(0)).isEqualTo("tinyint");
        assertThat(types.get(1)).isIn("decimal", "numeric");
    }

    @Test
    void checkConstraintsAreDeclared() {
        List<String> levels = jdbc.queryForList(
                "select constraint_name from information_schema.table_constraints " +
                        "where table_name='team_task_assignee' and constraint_type='CHECK' order by constraint_name",
                String.class);
        assertThat(levels).contains("chk_team_assignee_completed_fatigue_level",
                "chk_team_assignee_completed_fatigue_weight");
    }

    @Test
    void dailyAggregationIndexExists() {
        List<String> indexes = jdbc.queryForList(
                "select distinct index_name from information_schema.indexes where table_name='team_task_assignee'",
                String.class);
        assertThat(indexes).contains("idx_team_assignee_user_completed_at");
    }

    @Test
    void constraintsAreEnforced() {
        assertThatThrownBy(() -> insertAssignee(1L, 1L, 6, null))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> insertAssignee(2L, 2L, 3, "0"))
                .isInstanceOf(DataIntegrityViolationException.class);
        insertAssignee(3L, 3L, 5, "9.500");
        insertAssignee(4L, 4L, null, null);
    }

    private void insertAssignee(long taskId, long userId, Integer level, String weight) {
        jdbc.update("insert into team_task_assignee (task_id, user_id, completed_fatigue_level, completed_fatigue_weight) values (?,?,?,?)",
                taskId, userId, level, weight == null ? null : new java.math.BigDecimal(weight));
        jdbc.update("delete from team_task_assignee where task_id=? and user_id=?", taskId, userId);
    }
}