package com.dayliane.schedule;

import com.dayliane.auth.AuthService;
import com.dayliane.common.BusinessException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest
@ActiveProfiles("test")
class ScheduleLogicRegressionTests {

    @Autowired AuthService authService;
    @Autowired ScheduleService scheduleService;
    @Autowired JdbcTemplate jdbc;

    @BeforeEach
    void cleanDatabase() {
        for (String table : List.of("auth_revoked_access_token", "auth_refresh_token", "reminder", "schedule", "task_group", "user")) {
            jdbc.update("delete from " + ("user".equals(table) ? "`user`" : table));
        }
    }

    @Test
    void dateFiltersUseTheUsersConfiguredTimezone() {
        long userId = authService.register("15100000001", "Abc12345", "Timezone User", "America/Los_Angeles");
        scheduleService.createSchedule(userId, Map.of(
                "title", "Local August ninth",
                "timeType", "deadline_task",
                "deadlineTime", "2099-08-10T01:00:00Z"
        ));

        Map<String, Object> localNinth = scheduleService.listSchedules(userId, 1, 20, null, null, null, "2099-08-09", "2099-08-09");
        Map<String, Object> localTenth = scheduleService.listSchedules(userId, 1, 20, null, null, null, "2099-08-10", "2099-08-10");

        assertThat((List<?>) localNinth.get("list")).hasSize(1);
        assertThat((List<?>) localTenth.get("list")).isEmpty();
        assertThatThrownBy(() -> scheduleService.listSchedules(userId, 1, 20, null, null, null, "not-a-date", null))
                .isInstanceOf(BusinessException.class)
                .extracting(ex -> ((BusinessException) ex).getCode())
                .isEqualTo(400);
    }

    @Test
    void statusRestorationOnlyResumesTheCurrentFutureReminder() {
        long userId = authService.register("15100000002", "Abc12345", "Reminder User", "Asia/Shanghai");
        long scheduleId = ((Number) scheduleService.createSchedule(userId, Map.of(
                "title", "Restorable reminder",
                "timeType", "deadline_task",
                "deadlineTime", "2099-08-12T00:00:00Z",
                "remindAt", "2099-08-10T00:00:00Z"
        )).get("id")).longValue();

        scheduleService.updateSchedule(scheduleId, userId, Map.of("remindAt", "2099-08-11T00:00:00Z"));
        assertThat(count("select count(*) from reminder where target_id=? and status='cancelled'", scheduleId)).isEqualTo(1);
        assertThat(count("select count(*) from reminder where target_id=? and status='pending'", scheduleId)).isEqualTo(1);

        scheduleService.setScheduleStatus(scheduleId, userId, "completed");
        assertThat(count("select count(*) from reminder where target_id=? and status='paused'", scheduleId)).isEqualTo(1);
        assertThatThrownBy(() -> scheduleService.setScheduleStatus(scheduleId, userId, "cancelled"))
                .isInstanceOf(BusinessException.class);

        scheduleService.setScheduleStatus(scheduleId, userId, "pending");
        assertThat(count("select count(*) from reminder where target_id=? and status='pending'", scheduleId)).isEqualTo(1);
        assertThat(count("select count(*) from reminder where target_id=? and status='cancelled'", scheduleId)).isEqualTo(1);
    }

    private int count(String sql, Object... args) {
        Integer value = jdbc.queryForObject(sql, Integer.class, args);
        return value == null ? 0 : value;
    }
}
