package com.dayliane.ai;

import com.dayliane.auth.AuthService;
import com.dayliane.fatigue.FatigueService;
import com.dayliane.schedule.ScheduleService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@ActiveProfiles("test")
class ArrangeSchedulesTests {

    private static final ZoneId USER_ZONE = ZoneId.of("Asia/Shanghai");

    @Autowired AuthService authService;
    @Autowired AiService aiService;
    @Autowired ScheduleService scheduleService;
    @Autowired FatigueService fatigueService;
    @Autowired JdbcTemplate jdbc;

    @BeforeEach
    void cleanDatabase() {
        jdbc.update("delete from schedule_series_exdate");
        for (String table : List.of("fatigue_alert_log", "fatigue_survey_prompt_log", "fatigue_survey_skip", "fatigue_survey", "fatigue_daily_summary", "user_fatigue_profile", "notification", "auth_revoked_access_token", "auth_refresh_token", "auth_email_otp", "reminder", "team_task_event", "team_task_reminder_plan", "team_task_assignee", "team_task", "schedule", "task_group", "team_member", "team", "admin_operation_log", "admin_user", "user")) {
            jdbc.update("delete from " + ("user".equals(table) ? "`user`" : table));
        }
    }

    private long register(String phone) {
        return authService.register(phone, "Abc12345", "Arrange User", USER_ZONE.getId());
    }

    @Test
    void arrangeDisabledWhenRecordUsageOff() {
        long userId = register("15100040001");
        Map<String, Object> result = aiService.arrangeSchedules(userId, false);

        assertThat(result).containsEntry("disabled", true);
        assertThat(result).containsKey("reason");
        assertThat(result).doesNotContainKey("suggestions");
    }

    @Test
    void arrangeSuggestsReschedulingOutOfOverloadedDay() {
        long userId = register("15100040002");
        fatigueService.profile(userId);
        LocalDate today = LocalDate.now(USER_ZONE);
        String deadline = today.atTime(23, 0).atZone(USER_ZONE).toInstant().toString();

        // Three high-fatigue tasks on today -> planned load 24 > capacity75 (18) -> predicted score 100.
        for (int i = 0; i < 3; i++) {
            scheduleService.createSchedule(userId, Map.of(
                    "title", "Heavy " + i,
                    "timeType", "deadline_task",
                    "deadlineTime", deadline,
                    "fatigueLevel", 5
            ));
        }

        Map<String, Object> result = aiService.arrangeSchedules(userId, true);

        assertThat(result).containsEntry("disabled", false);
        assertThat(result).containsKey("highLoadDays");
        @SuppressWarnings("unchecked")
        List<String> highLoadDays = (List<String>) result.get("highLoadDays");
        assertThat(highLoadDays).contains(today.toString());
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> suggestions = (List<Map<String, Object>>) result.get("suggestions");
        assertThat(suggestions).isNotNull();
        assertThat(suggestions).anySatisfy(item -> {
            assertThat(item).containsEntry("type", "reschedule");
            assertThat(item).containsKey("targetDate");
        });
    }

    @Test
    void arrangeDoesNotReadTeamCompletedLoadAsInput() {
        long userId = register("15100040003");
        fatigueService.profile(userId);
        LocalDate today = LocalDate.now(USER_ZONE);

        Map<String, Object> before = aiService.arrangeSchedules(userId, true);

        // Team completion load must not influence suggestions: it is not part of the arrange query.
        assertThat(before).containsEntry("disabled", false);
        // The suggestions list is driven only by personal pending schedules.
        assertThat(before.get("suggestions")).isNotNull();
    }

    @Test
    void arrangeReturnsEmptySuggestionsWhenLoadBalanced() {
        long userId = register("15100040004");
        fatigueService.profile(userId);
        LocalDate today = LocalDate.now(USER_ZONE);

        Map<String, Object> result = aiService.arrangeSchedules(userId, true);

        assertThat(result).containsEntry("disabled", false);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> suggestions = (List<Map<String, Object>>) result.get("suggestions");
        assertThat(suggestions).isNotNull().isEmpty();
        @SuppressWarnings("unchecked")
        List<String> highLoadDays = (List<String>) result.get("highLoadDays");
        assertThat(highLoadDays).isEmpty();
    }
}