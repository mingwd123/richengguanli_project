package com.dayliane.fatigue;

import com.dayliane.auth.AuthService;
import com.dayliane.common.BusinessException;
import com.dayliane.schedule.ScheduleService;
import com.dayliane.team.TeamService;
import com.dayliane.teamtask.TeamTaskService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest
@ActiveProfiles("test")
class FatigueReportTests {

    private static final ZoneId USER_ZONE = ZoneId.of("Asia/Shanghai");

    @Autowired AuthService authService;
    @Autowired FatigueService fatigueService;
    @Autowired ScheduleService scheduleService;
    @Autowired TeamService teamService;
    @Autowired TeamTaskService teamTaskService;
    @Autowired JdbcTemplate jdbc;

    @BeforeEach
    void cleanDatabase() {
        for (String table : List.of(
                "fatigue_alert_log", "fatigue_survey_prompt_log", "fatigue_survey_skip", "fatigue_survey", "fatigue_daily_summary", "user_fatigue_profile",
                "notification", "notification_preference", "reminder_preset", "reminder",
                "team_task_event", "team_task_reminder_plan", "team_task_assignee", "team_task",
                "schedule_series_exdate", "schedule", "task_group", "team_member", "team",
                "auth_revoked_access_token", "auth_refresh_token", "auth_email_otp", "user")) {
            jdbc.update("delete from " + ("user".equals(table) ? "`user`" : table));
        }
    }

    @Test
    void weekReportAggregatesLoadsRatePeakAndSurveyAverage() {
        long userId = register("15120001001", "Report User");
        LocalDate anchor = LocalDate.of(2026, 8, 20); // Thursday -> week Mon 08-17 .. Sun 08-23

        long s1 = id(scheduleService.createSchedule(userId, Map.of(
                "title", "Tue task", "timeType", "deadline_task",
                "deadlineTime", at(LocalDate.of(2026, 8, 18), 12), "fatigueLevel", 3)));
        long s2 = id(scheduleService.createSchedule(userId, Map.of(
                "title", "Thu task", "timeType", "deadline_task",
                "deadlineTime", at(LocalDate.of(2026, 8, 20), 12), "fatigueLevel", 4)));
        id(scheduleService.createSchedule(userId, Map.of(
                "title", "Next week task", "timeType", "deadline_task",
                "deadlineTime", at(LocalDate.of(2026, 8, 24), 12), "fatigueLevel", 2)));

        scheduleService.setScheduleStatus(s1, userId, "completed");
        scheduleService.setScheduleStatus(s2, userId, "completed");
        jdbc.update("update schedule set completed_at=? where id=?",
                Timestamp.from(LocalDate.of(2026, 8, 18).atTime(12, 0).atZone(USER_ZONE).toInstant()), s1);
        jdbc.update("update schedule set completed_at=? where id=?",
                Timestamp.from(LocalDate.of(2026, 8, 19).atTime(12, 0).atZone(USER_ZONE).toInstant()), s2);

        insertSurvey(userId, LocalDate.of(2026, 8, 18), 40);
        insertSurvey(userId, LocalDate.of(2026, 8, 19), 60);

        Map<String, Object> report = fatigueService.report(userId, "week", "2026-08-20");
        assertThat(report).containsEntry("period", "week")
                .containsEntry("dateFrom", "2026-08-17")
                .containsEntry("dateTo", "2026-08-23");

        @SuppressWarnings("unchecked")
        Map<String, Object> summary = (Map<String, Object>) report.get("summary");
        assertThat(decimal(summary.get("personalPlannedLoad"))).isEqualByComparingTo("8");
        assertThat(decimal(summary.get("personalCompletedLoad"))).isEqualByComparingTo("8");
        assertThat(decimal(summary.get("teamCompletedLoad"))).isEqualByComparingTo("0");
        assertThat(decimal(summary.get("totalCompletedLoad"))).isEqualByComparingTo("8");
        assertThat(summary).containsEntry("personalScheduleCount", 2).containsEntry("personalCompletedCount", 2);
        assertThat(summary.get("personalCompletionRate")).isNotNull();
        assertThat(decimal(summary.get("personalCompletionRate"))).isEqualByComparingTo("1");
        assertThat(summary).containsEntry("peakDay", "2026-08-20");
        assertThat(decimal(summary.get("peakDayLoad"))).isEqualByComparingTo("5");
        assertThat(decimal(summary.get("surveyScoreAvg"))).isEqualByComparingTo("50");
    }

    @Test
    void monthReportHonorsNaturalMonthBoundary() {
        long userId = register("15120001002", "Month User");
        scheduleService.createSchedule(userId, Map.of(
                "title", "Aug 31", "timeType", "deadline_task",
                "deadlineTime", at(LocalDate.of(2026, 8, 31), 12), "fatigueLevel", 5));
        scheduleService.createSchedule(userId, Map.of(
                "title", "Sep 1", "timeType", "deadline_task",
                "deadlineTime", at(LocalDate.of(2026, 9, 1), 12), "fatigueLevel", 5));

        Map<String, Object> report = fatigueService.report(userId, "month", "2026-08-20");
        assertThat(report).containsEntry("dateFrom", "2026-08-01").containsEntry("dateTo", "2026-08-31");

        @SuppressWarnings("unchecked")
        Map<String, Object> summary = (Map<String, Object>) report.get("summary");
        assertThat(summary).containsEntry("personalScheduleCount", 1);
        assertThat(decimal(summary.get("personalPlannedLoad"))).isEqualByComparingTo("8");
    }

    @Test
    void emptyPeriodReturnsNullRateAndGuidesFrontend() {
        long userId = register("15120001003", "Empty User");
        Map<String, Object> report = fatigueService.report(userId, "week", "2026-08-20");

        @SuppressWarnings("unchecked")
        Map<String, Object> summary = (Map<String, Object>) report.get("summary");
        assertThat(summary.get("personalCompletionRate")).isNull();
        assertThat(summary.get("surveyScoreAvg")).isNull();
        assertThat(summary.get("peakDay")).isNull();
        assertThat(decimal(summary.get("personalPlannedLoad"))).isEqualByComparingTo("0");

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> trend = (List<Map<String, Object>>) report.get("trend");
        assertThat(trend).hasSize(7);
    }

    @Test
    void teamCompletedLoadAggregatesIntoSeparateBucketOnCompletionDate() {
        long owner = register("15120001004", "Owner");
        long member = register("15120001005", "Member");
        long teamId = id(teamService.createTeam(owner, "Report Team"));
        teamService.joinTeam(member, String.valueOf(teamService.teamDetail(teamId, owner).get("inviteCode")));
        long taskId = id(teamTaskService.createTeamTask(owner, Map.of("teamId", teamId, "title", "Team load", "assigneeUserIds", List.of(member))));
        teamTaskService.teamTaskAssigneeTransition(taskId, member, "accepted", List.of("pending"));
        teamTaskService.completeTeamTask(taskId, member, Map.of("fatigueLevel", 5));

        Map<String, Object> report = fatigueService.report(member, "week");

        @SuppressWarnings("unchecked")
        Map<String, Object> summary = (Map<String, Object>) report.get("summary");
        BigDecimal expected = fatigueService.currentWeight(member, 5);
        assertThat(decimal(summary.get("teamCompletedLoad"))).isEqualByComparingTo(expected.toString());
        assertThat(decimal(summary.get("totalCompletedLoad"))).isEqualByComparingTo(expected.toString());
        assertThat(decimal(summary.get("personalCompletedLoad"))).isEqualByComparingTo("0");
        assertThat(summary.get("personalCompletionRate")).isNull();
    }

    @Test
    void reportRejectsInvalidPeriod() {
        long userId = register("15120001006", "Invalid Period");
        assertThatThrownBy(() -> fatigueService.report(userId, "year"))
                .isInstanceOf(BusinessException.class)
                .extracting(ex -> ((BusinessException) ex).getCode())
                .isEqualTo(400);
    }

    @Test
    void reportMaterializesRecurringInstancesWithinRange() {
        long userId = register("15120001007", "Recurring Report User");
        LocalDate nextMonthStart = LocalDate.now(USER_ZONE).plusMonths(1).withDayOfMonth(1);
        LocalDate today = LocalDate.now(USER_ZONE);

        scheduleService.createSchedule(userId, Map.of(
                "title", "Daily recurring",
                "timeType", "deadline_task",
                "deadlineTime", today.atTime(12, 0).atZone(USER_ZONE).toInstant().toString(),
                "rrule", "FREQ=DAILY"));

        Map<String, Object> report = fatigueService.report(userId, "month", nextMonthStart.toString());
        assertThat(report).containsEntry("dateFrom", nextMonthStart.toString())
                .containsEntry("dateTo", nextMonthStart.with(java.time.temporal.TemporalAdjusters.lastDayOfMonth()).toString());

        @SuppressWarnings("unchecked")
        Map<String, Object> summary = (Map<String, Object>) report.get("summary");
        assertThat(summary).containsEntry("personalScheduleCount", nextMonthStart.lengthOfMonth());
        assertThat(decimal(summary.get("personalPlannedLoad"))).isEqualByComparingTo(String.valueOf(nextMonthStart.lengthOfMonth() * 3));
    }

    private void insertSurvey(long userId, LocalDate date, int score) {
        jdbc.update("insert into fatigue_survey (user_id,local_date,timezone_snapshot,score,weights_snapshot) values (?,?,?,?,?)",
                userId, date, USER_ZONE.getId(), score, "{}");
    }

    private long register(String phone, String nickname) {
        return authService.register(phone, "Abc12345", nickname, USER_ZONE.getId());
    }

    private static String at(LocalDate date, int hour) {
        return date.atTime(hour, 0).atZone(USER_ZONE).toOffsetDateTime().toString();
    }

    private static long id(Map<String, Object> item) {
        return ((Number) item.get("id")).longValue();
    }

    private static BigDecimal decimal(Object value) {
        if (value == null) return null;
        if (value instanceof BigDecimal decimal) return decimal;
        return new BigDecimal(String.valueOf(value));
    }
}