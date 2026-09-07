package com.dayliane.schedule;

import com.dayliane.auth.AuthService;
import com.dayliane.common.BusinessException;
import com.dayliane.team.TeamService;
import com.dayliane.teamtask.TeamTaskService;
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
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest
@ActiveProfiles("test")
class ScheduleIcalTests {

    private static final ZoneId USER_ZONE = ZoneId.of("Asia/Shanghai");

    @Autowired AuthService authService;
    @Autowired ScheduleService scheduleService;
    @Autowired TeamService teamService;
    @Autowired TeamTaskService teamTaskService;
    @Autowired JdbcTemplate jdbc;

    @BeforeEach
    void cleanDatabase() {
        jdbc.update("delete from schedule_series_exdate");
        for (String table : List.of("fatigue_alert_log", "fatigue_survey_prompt_log", "fatigue_survey_skip", "fatigue_survey", "fatigue_daily_summary", "user_fatigue_profile", "notification", "auth_revoked_access_token", "auth_refresh_token", "auth_email_otp", "reminder", "team_task_event", "team_task_reminder_plan", "team_task_assignee", "team_task", "schedule", "task_group", "team_member", "team", "admin_operation_log", "admin_user", "user")) {
            jdbc.update("delete from " + ("user".equals(table) ? "`user`" : table));
        }
    }

    private long register(String phone, ZoneId zone) {
        return authService.register(phone, "Abc12345", "Ical User", zone.getId());
    }

    @Test
    void subscribeTokenIsGeneratedOnceAndReused() {
        long userId = register("15100030001", USER_ZONE);

        Map<String, Object> first = scheduleService.subscribeToken(userId);
        String token1 = String.valueOf(first.get("token"));
        assertThat(token1).hasSize(64);
        assertThat(String.valueOf(first.get("path"))).isEqualTo("/api/v1/schedules/ical?token=" + token1);

        Map<String, Object> second = scheduleService.subscribeToken(userId);
        assertThat(String.valueOf(second.get("token"))).isEqualTo(token1);
    }

    @Test
    void resetSubscribeTokenRotatesAndInvalidatesOldToken() {
        long userId = register("15100030002", USER_ZONE);
        String oldToken = String.valueOf(scheduleService.subscribeToken(userId).get("token"));

        String newToken = String.valueOf(scheduleService.resetSubscribeToken(userId).get("token"));
        assertThat(newToken).isNotEqualTo(oldToken).hasSize(64);

        assertThat(scheduleService.calendarIcs(newToken)).contains("BEGIN:VCALENDAR");
        assertThatThrownBy(() -> scheduleService.calendarIcs(oldToken))
                .isInstanceOf(BusinessException.class)
                .extracting(ex -> ((BusinessException) ex).getCode())
                .isEqualTo(404);
    }

    @Test
    void calendarIcsExportsPendingPersonalSchedulesButNotCompletedOrCancelled() {
        ZoneId zone = USER_ZONE;
        long userId = register("15100030003", zone);
        LocalDate today = LocalDate.now(zone);

        long pendingId = id(scheduleService.createSchedule(userId, Map.of(
                "title", "Pending task",
                "timeType", "deadline_task",
                "deadlineTime", today.atTime(18, 30).atZone(zone).toInstant().toString()
        )));
        long completedId = id(scheduleService.createSchedule(userId, Map.of(
                "title", "Done task",
                "timeType", "deadline_task",
                "deadlineTime", today.atTime(9, 0).atZone(zone).toInstant().toString()
        )));
        long cancelledId = id(scheduleService.createSchedule(userId, Map.of(
                "title", "Cancelled task",
                "timeType", "deadline_task",
                "deadlineTime", today.atTime(11, 0).atZone(zone).toInstant().toString()
        )));
        scheduleService.setScheduleStatus(completedId, userId, "completed");
        scheduleService.setScheduleStatus(cancelledId, userId, "cancelled");

        String token = String.valueOf(scheduleService.subscribeToken(userId).get("token"));
        String ics = scheduleService.calendarIcs(token);

        assertThat(ics).contains("BEGIN:VCALENDAR").contains("END:VCALENDAR");
        assertThat(ics).contains("UID:schedule-" + pendingId + "@dayliane");
        assertThat(ics).contains("SUMMARY:Pending task");
        assertThat(ics).doesNotContain("UID:schedule-" + completedId + "@dayliane");
        assertThat(ics).doesNotContain("UID:schedule-" + cancelledId + "@dayliane");
    }

    @Test
    void calendarIcsIncludesAssignedTeamTasksButNotCompletedOrRejected() {
        ZoneId zone = USER_ZONE;
        long owner = register("15100030004", zone);
        long member = register("15100030005", zone);
        long teamId = id(teamService.createTeam(owner, "Ical Team"));
        teamService.joinTeam(member, text(teamService.teamDetail(teamId, owner), "inviteCode"));

        long acceptedId = id(teamTaskService.createTeamTask(owner, Map.of("teamId", teamId, "title", "Active team task",
                "assigneeUserIds", List.of(member), "deadlineTime", LocalDate.now(zone).atTime(17, 0).atZone(zone).toInstant().toString())));
        teamTaskService.teamTaskAssigneeTransition(acceptedId, member, "accepted", List.of("pending"));

        long rejectedId = id(teamTaskService.createTeamTask(owner, Map.of("teamId", teamId, "title", "Rejected team task",
                "assigneeUserIds", List.of(member), "deadlineTime", LocalDate.now(zone).atTime(17, 0).atZone(zone).toInstant().toString())));
        teamTaskService.teamTaskAssigneeTransition(rejectedId, member, "rejected", List.of("pending"));

        String token = String.valueOf(scheduleService.subscribeToken(member).get("token"));
        String ics = scheduleService.calendarIcs(token);

        assertThat(ics).contains("UID:team-task-" + acceptedId + "@dayliane");
        assertThat(ics).contains("SUMMARY:Active team task");
        assertThat(ics).doesNotContain("UID:team-task-" + rejectedId + "@dayliane");
    }

    @Test
    void calendarIcsRejectsBlankOrUnknownToken() {
        register("15100030006", USER_ZONE);

        assertThatThrownBy(() -> scheduleService.calendarIcs(""))
                .isInstanceOf(BusinessException.class)
                .extracting(ex -> ((BusinessException) ex).getCode())
                .isEqualTo(404);
        assertThatThrownBy(() -> scheduleService.calendarIcs("unknown-token"))
                .isInstanceOf(BusinessException.class)
                .extracting(ex -> ((BusinessException) ex).getCode())
                .isEqualTo(404);
    }

    // A pending repeat series is exported as one VEVENT carrying RRULE (not one VEVENT per materialized occurrence).
    @Test
    void calendarIcsOnlyExportsPendingInstancesOfRepeatSeries() {
        ZoneId zone = USER_ZONE;
        long userId = register("15100030007", zone);
        LocalDate today = LocalDate.now(zone);

        Map<String, Object> created = scheduleService.createSchedule(userId, Map.of(
                "title", "Daily",
                "timeType", "deadline_task",
                "deadlineTime", today.atTime(12, 0).atZone(zone).toInstant().toString(),
                "rrule", "FREQ=DAILY"
        ));
        String seriesId = String.valueOf(created.get("seriesId"));

        String token = String.valueOf(scheduleService.subscribeToken(userId).get("token"));
        String ics = scheduleService.calendarIcs(token);

        assertThat(countOccurrences(ics, "BEGIN:VEVENT")).isEqualTo(1);
        assertThat(ics).contains("UID:series-" + seriesId + "@dayliane");
        assertThat(ics).contains("RRULE:FREQ=DAILY");
    }

    private static int countOccurrences(String haystack, String needle) {
        int count = 0;
        int idx = 0;
        while ((idx = haystack.indexOf(needle, idx)) >= 0) {
            count++;
            idx += needle.length();
        }
        return count;
    }

    private static long id(Map<String, Object> item) {
        return ((Number) item.get("id")).longValue();
    }

    private static String text(Map<String, Object> item, String key) {
        return String.valueOf(item.get(key));
    }
}