package com.dayliane.fatigue;

import com.dayliane.auth.AuthService;
import com.dayliane.common.BusinessException;
import com.dayliane.schedule.ScheduleService;
import com.dayliane.team.TeamService;
import com.dayliane.teamtask.TeamTaskService;
import com.dayliane.user.UserService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest
@ActiveProfiles("test")
class FatigueLogicRegressionTests {

    private static final ZoneId USER_ZONE = ZoneId.of("Asia/Shanghai");

    @Autowired AuthService authService;
    @Autowired FatigueService fatigueService;
    @Autowired ScheduleService scheduleService;
    @Autowired TeamService teamService;
    @Autowired TeamTaskService teamTaskService;
    @Autowired UserService userService;
    @Autowired JdbcTemplate jdbc;

    @BeforeEach
    void cleanDatabase() {
        for (String table : List.of(
                "fatigue_alert_log", "fatigue_survey_prompt_log", "fatigue_survey_skip", "fatigue_survey", "fatigue_daily_summary", "user_fatigue_profile",
                "notification", "notification_preference", "reminder_preset", "reminder",
                "team_task_event", "team_task_reminder_plan", "team_task_assignee", "team_task",
                "schedule", "task_group", "team_member", "team", "auth_revoked_access_token",
                "auth_refresh_token", "auth_email_otp", "user")) {
            jdbc.update("delete from " + ("user".equals(table) ? "`user`" : table));
        }
    }

    @Test
    void plannedLoadUsesConfiguredWeightsAndCompletedSchedulesRemainPlanned() {
        long userId = register("15100001001", "Fatigue Weights");
        LocalDate date = LocalDate.now(USER_ZONE);
        for (int level = 1; level <= 5; level++) {
            scheduleService.createSchedule(userId, Map.of(
                    "title", "Level " + level,
                    "timeType", "deadline_task",
                    "deadlineTime", at(date, 12 + level),
                    "fatigueLevel", level,
                    "urgencyLevel", 3));
        }

        Map<String, Object> before = fatigueService.daily(userId, date);
        assertThat(decimal(before, "plannedLoad")).isEqualByComparingTo("19");
        assertThat(before).containsEntry("pendingCount", 5).containsEntry("completedCount", 0);

        long levelFiveId = jdbc.queryForObject("select id from schedule where user_id=? and title=?", Long.class, userId, "Level 5");
        scheduleService.setScheduleStatus(levelFiveId, userId, "completed");

        Map<String, Object> after = fatigueService.daily(userId, date);
        assertThat(decimal(after, "plannedLoad")).isEqualByComparingTo("19");
        assertThat(decimal(after, "completedLoad")).isEqualByComparingTo("8");
        assertThat(after).containsEntry("pendingCount", 4).containsEntry("completedCount", 1);
        assertThat(jdbc.queryForObject("select completed_fatigue_level from schedule where id=?", Integer.class, levelFiveId)).isEqualTo(5);
        assertThat(decimal(jdbc.queryForObject("select completed_fatigue_weight from schedule where id=?", BigDecimal.class, levelFiveId))).isEqualByComparingTo("8");
    }

    @Test
    void completionSnapshotKeepsOriginalWeightWhenScheduleLevelChanges() {
        long userId = register("15100001002", "Snapshot User");
        LocalDate date = LocalDate.now(USER_ZONE);
        long scheduleId = id(scheduleService.createSchedule(userId, Map.of(
                "title", "Snapshot schedule",
                "timeType", "deadline_task",
                "deadlineTime", at(date, 13),
                "fatigueLevel", 2)));

        scheduleService.setScheduleStatus(scheduleId, userId, "completed");
        scheduleService.updateSchedule(scheduleId, userId, Map.of("fatigueLevel", 5));

        assertThat(jdbc.queryForObject("select fatigue_level from schedule where id=?", Integer.class, scheduleId)).isEqualTo(5);
        assertThat(jdbc.queryForObject("select completed_fatigue_level from schedule where id=?", Integer.class, scheduleId)).isEqualTo(2);
        assertThat(decimal(jdbc.queryForObject("select completed_fatigue_weight from schedule where id=?", BigDecimal.class, scheduleId))).isEqualByComparingTo("2");
    }

    @Test
    void durationScheduleCountsOnlyOnItsStartDate() {
        long userId = register("15100001003", "Cross Day User");
        LocalDate date = LocalDate.now(USER_ZONE);
        scheduleService.createSchedule(userId, Map.of(
                "title", "Overnight duration",
                "timeType", "duration_task",
                "startTime", at(date, 23),
                "endTime", at(date.plusDays(1), 1),
                "fatigueLevel", 4));

        assertThat(decimal(fatigueService.daily(userId, date), "plannedLoad")).isEqualByComparingTo("5");
        assertThat(decimal(fatigueService.daily(userId, date.plusDays(1)), "plannedLoad")).isEqualByComparingTo("0");
    }

    @Test
    void teamTasksAreExcludedFromPersonalFatigueLoad() {
        long userId = register("15100001004", "Team Isolation");
        long teamId = id(teamService.createTeam(userId, "Fatigue Isolation Team"));
        LocalDate date = LocalDate.now(USER_ZONE);
        teamTaskService.createTeamTask(userId, Map.of(
                "teamId", teamId,
                "title", "Team-only task",
                "startTime", at(date, 10),
                "deadlineTime", at(date, 11),
                "assigneeUserIds", List.of(userId)));

        Map<String, Object> daily = fatigueService.daily(userId, date);
        assertThat(decimal(daily, "plannedLoad")).isEqualByComparingTo("0");
        assertThat(decimal(daily, "completedLoad")).isEqualByComparingTo("0");
        assertThat(daily).containsEntry("pendingCount", 0).containsEntry("completedCount", 0);
    }

    @Test
    void surveyCanBeModifiedAndInvalidationClearsPreviousCapacityAfter() {
        long userId = register("15100001005", "Survey User");
        LocalDate date = LocalDate.now(USER_ZONE);
        long scheduleId = id(scheduleService.createSchedule(userId, Map.of(
                "title", "Survey workload",
                "timeType", "deadline_task",
                "deadlineTime", at(date, 9),
                "fatigueLevel", 3)));
        scheduleService.setScheduleStatus(scheduleId, userId, "completed");

        Map<String, Object> first = fatigueService.submitSurvey(userId, Map.of(
                "localDate", date.toString(),
                "score", 50,
                "externalFactorLevel", 0,
                "externalFactorTags", List.of()));
        assertThat(first.get("survey")).isNotNull();
        jdbc.update("update fatigue_survey set capacity_after=? where user_id=? and local_date=?", BigDecimal.valueOf(21), userId, date);

        fatigueService.submitSurvey(userId, Map.of(
                "localDate", date.toString(),
                "score", 70,
                "externalFactorLevel", 1,
                "externalFactorTags", List.of("睡眠不足")));

        Map<String, Object> survey = jdbc.queryForMap("select score,external_factor_level,capacity_after,model_eligible,ineligible_reason,learning_weight from fatigue_survey where user_id=? and local_date=?", userId, date);
        assertThat(count("select count(*) from fatigue_survey where user_id=? and local_date=?", userId, date)).isEqualTo(1);
        assertThat(survey).containsEntry("score", 70).containsEntry("external_factor_level", 1).containsEntry("model_eligible", true).containsEntry("ineligible_reason", null);
        assertThat(decimal(survey.get("learning_weight"))).isEqualByComparingTo("0.5");
        assertThat(survey.get("capacity_after")).isNull();
    }

    @Test
    void previewDoesNotPersistDailySummary() {
        long userId = register("15100001006", "Preview User");
        LocalDate date = LocalDate.now(USER_ZONE);

        Map<String, Object> preview = fatigueService.preview(userId, Map.of(
                "localDate", date.toString(),
                "operation", "create",
                "fatigueLevel", 5));

        assertThat(decimal(((Map<String, Object>) preview.get("before")), "plannedLoad")).isEqualByComparingTo("0");
        assertThat(decimal(((Map<String, Object>) preview.get("after")), "plannedLoad")).isEqualByComparingTo("8");
        assertThat(count("select count(*) from fatigue_daily_summary where user_id=? and local_date=?", userId, date)).isZero();
    }

    @Test
    void historyCanInitializeTheProfileOnFirstRead() {
        long userId = register("15100001011", "History User");
        LocalDate date = LocalDate.now(USER_ZONE);
        jdbc.update("delete from user_fatigue_profile where user_id=?", userId);

        Map<String, Object> history = fatigueService.history(userId, date.minusDays(1).toString(), date.toString());

        assertThat(count("select count(*) from user_fatigue_profile where user_id=?", userId)).isEqualTo(1);
        assertThat(history.get("list")).isEqualTo(List.of());
    }

    @Test
    void repeatedExternalFactorsAreFlaggedAsPotentialBaselineSignals() {
        long userId = register("15100001012", "Recurring Factor User");
        LocalDate date = LocalDate.now(USER_ZONE);
        fatigueService.profile(userId);
        for (int index = 0; index < 4; index++) {
            jdbc.update("insert into fatigue_survey (user_id,local_date,timezone_snapshot,score,external_factor_level,external_factor_tags,completed_load_snapshot,weights_snapshot,capacity_before,model_eligible,learning_weight,completed_level_counts,algorithm_version) values (?,?,?,?,1,?,1,'{}',18,true,0.5,'{}',2)",
                    userId, date.minusDays(index), USER_ZONE.getId(), 50, "[\"sleep_loss\"]");
        }

        Map<String, Object> survey = fatigueService.surveyToday(userId, date.toString());
        List<Map<String, Object>> recurring = (List<Map<String, Object>>) survey.get("recurringExternalFactors");

        assertThat(recurring).containsExactly(Map.of("tag", "sleep_loss", "days", 4));
    }

    @Test
    void recalibrationIsDeterministicAcrossRepeatedRuns() {
        long userId = register("15100001007", "Deterministic User");
        LocalDate start = LocalDate.now(USER_ZONE).minusDays(10);
        for (int index = 0; index < 7; index++) {
            jdbc.update("insert into fatigue_survey (user_id,local_date,timezone_snapshot,score,external_factor_level,external_factor_tags,completed_load_snapshot,weights_snapshot,capacity_before,model_eligible,learning_weight,completed_level_counts,algorithm_version) values (?,?,?,?,0,'[]',?,'{}',18,true,1,?,2)",
                    userId, start.plusDays(index), USER_ZONE.getId(), 60 + index, BigDecimal.valueOf(12 + index), "{\"1\":0,\"2\":0,\"3\":1,\"4\":0,\"5\":0}");
        }

        BigDecimal first = decimal(((Map<String, Object>) fatigueService.recalibrateModel(userId)).get("capacity75"));
        BigDecimal second = decimal(((Map<String, Object>) fatigueService.recalibrateModel(userId)).get("capacity75"));

        assertThat(second).isEqualByComparingTo(first);
        assertThat(jdbc.queryForObject("select valid_survey_days from user_fatigue_profile where user_id=?", Integer.class, userId)).isEqualTo(7);
    }

    @Test
    void restoringCompletedScheduleInvalidatesItsTrainingSurvey() {
        long userId = register("15100001008", "Invalidation User");
        LocalDate date = LocalDate.now(USER_ZONE);
        long scheduleId = id(scheduleService.createSchedule(userId, Map.of(
                "title", "Completed workload",
                "timeType", "deadline_task",
                "deadlineTime", at(date, 16),
                "fatigueLevel", 4)));
        scheduleService.setScheduleStatus(scheduleId, userId, "completed");
        fatigueService.submitSurvey(userId, Map.of("localDate", date.toString(), "score", 70));

        scheduleService.setScheduleStatus(scheduleId, userId, "pending");

        Map<String, Object> survey = jdbc.queryForMap("select model_eligible,ineligible_reason,learning_weight from fatigue_survey where user_id=? and local_date=?", userId, date);
        assertThat(survey).containsEntry("model_eligible", false).containsEntry("ineligible_reason", "schedule_data_changed");
        assertThat(decimal(survey.get("learning_weight"))).isEqualByComparingTo("0");
    }

    @Test
    void disabledTrackingDoesNotPersistDailySummaries() {
        long userId = register("15100001009", "Tracking Disabled");
        LocalDate date = LocalDate.now(USER_ZONE);
        fatigueService.updatePreferences(userId, Map.of("fatigueTrackingEnabled", false));
        scheduleService.createSchedule(userId, Map.of(
                "title", "Untracked schedule",
                "timeType", "deadline_task",
                "deadlineTime", at(date, 17),
                "fatigueLevel", 5));

        Map<String, Object> daily = fatigueService.daily(userId, date);

        assertThat(daily).containsEntry("trackingEnabled", false);
        assertThat(decimal(daily, "plannedLoad")).isEqualByComparingTo("0");
        assertThat(count("select count(*) from fatigue_daily_summary where user_id=?", userId)).isZero();
    }

    @Test
    void completionUsesCurrentPersonalizedWeightAndAlertsAreScannedLater() {
        long userId = register("15100001010", "Personalized Snapshot");
        LocalDate date = LocalDate.now(USER_ZONE);
        fatigueService.profile(userId);
        jdbc.update("update user_fatigue_profile set level_5_weight=9.5 where user_id=?", userId);
        long scheduleId = id(scheduleService.createSchedule(userId, Map.of(
                "title", "High load",
                "timeType", "deadline_task",
                "deadlineTime", at(date, 18),
                "fatigueLevel", 5)));
        scheduleService.setScheduleStatus(scheduleId, userId, "completed");
        for (int index = 0; index < 2; index++) {
            long extraId = id(scheduleService.createSchedule(userId, Map.of(
                    "title", "High load " + index,
                    "timeType", "deadline_task",
                    "deadlineTime", at(date, 19 + index),
                    "fatigueLevel", 5)));
            scheduleService.setScheduleStatus(extraId, userId, "completed");
        }

        assertThat(decimal(jdbc.queryForObject("select completed_fatigue_weight from schedule where id=?", BigDecimal.class, scheduleId))).isEqualByComparingTo("9.5");
        assertThat(count("select count(*) from fatigue_alert_log where user_id=?", userId)).isZero();

        assertThat(fatigueService.scanFatigueAlerts()).isEqualTo(1);
        assertThat(count("select count(*) from fatigue_alert_log where user_id=?", userId)).isEqualTo(1);
        assertThat(count("select count(*) from notification where user_id=? and type='fatigue_actual_warning'", userId)).isEqualTo(1);
    }

    @Test
    void surveySkipsRemainIndependentForTodayAndYesterday() {
        ZoneId zone = ZoneOffset.UTC;
        long userId = authService.register("15100001013", "Abc12345", "Two Day Skip User", zone.getId());
        LocalDate today = LocalDate.now(zone);
        LocalDate yesterday = today.minusDays(1);
        for (LocalDate date : List.of(yesterday, today)) {
            Timestamp atNoon = Timestamp.from(date.atTime(12, 0).toInstant(ZoneOffset.UTC));
            jdbc.update("insert into schedule (user_id,title,time_type,deadline_time,status,completed_at,completed_fatigue_level,completed_fatigue_weight) values (?,?,'deadline_task',?,'completed',?,3,3)",
                    userId, "Completed " + date, atNoon, atNoon);
        }
        fatigueService.recalculateDates(userId, List.of(yesterday, today));

        assertThat(fatigueService.surveyToday(userId, yesterday.toString())).containsEntry("pending", true);
        assertThat(fatigueService.surveyToday(userId, today.toString())).containsEntry("pending", true);

        fatigueService.skipSurvey(userId, Map.of("localDate", yesterday.toString()));
        fatigueService.skipSurvey(userId, Map.of("localDate", today.toString()));

        assertThat(count("select count(*) from fatigue_survey_skip where user_id=?", userId)).isEqualTo(2);
        assertThat(fatigueService.surveyToday(userId, yesterday.toString())).containsEntry("pending", false);
        assertThat(fatigueService.surveyToday(userId, today.toString())).containsEntry("pending", false);
    }

    @Test
    void concurrentSurveySubmissionsRemainIdempotent() throws Exception {
        ZoneId zone = ZoneOffset.UTC;
        long userId = authService.register("15100001014", "Abc12345", "Concurrent Survey User", zone.getId());
        LocalDate date = LocalDate.now(zone);
        long scheduleId = id(scheduleService.createSchedule(userId, Map.of(
                "title", "Concurrent survey workload",
                "timeType", "deadline_task",
                "deadlineTime", date.atTime(12, 0).toInstant(ZoneOffset.UTC).toString(),
                "fatigueLevel", 4)));
        scheduleService.setScheduleStatus(scheduleId, userId, "completed");
        Map<String, Object> payload = Map.of("localDate", date.toString(), "score", 65);

        List<Map<String, Object>> results = runConcurrently(() -> fatigueService.submitSurvey(userId, payload));

        assertThat(results).hasSize(2).allSatisfy(result -> assertThat(result.get("survey")).isNotNull());
        assertThat(count("select count(*) from fatigue_survey where user_id=? and local_date=?", userId, date)).isEqualTo(1);
        assertThat(jdbc.queryForObject("select score from fatigue_survey where user_id=? and local_date=?", Integer.class, userId, date)).isEqualTo(65);
    }

    @Test
    void concurrentSurveyPromptScansCreateOneNotification() throws Exception {
        ZoneId zone = ZoneOffset.UTC;
        long userId = authService.register("15100001015", "Abc12345", "Concurrent Prompt User", zone.getId());
        LocalDate date = LocalDate.now(zone);
        long scheduleId = id(scheduleService.createSchedule(userId, Map.of(
                "title", "Prompt workload",
                "timeType", "deadline_task",
                "deadlineTime", date.atTime(12, 0).toInstant(ZoneOffset.UTC).toString(),
                "fatigueLevel", 3)));
        scheduleService.setScheduleStatus(scheduleId, userId, "completed");
        fatigueService.profile(userId);
        jdbc.update("update user_fatigue_profile set survey_time='00:00:00' where user_id=?", userId);

        List<Integer> created = runConcurrently(fatigueService::scanSurveyPrompts);

        assertThat(created.stream().mapToInt(Integer::intValue).sum()).isEqualTo(1);
        assertThat(count("select count(*) from fatigue_survey_prompt_log where user_id=? and local_date=?", userId, date)).isEqualTo(1);
        assertThat(count("select count(*) from notification where user_id=? and type='fatigue_survey' and local_date=?", userId, date)).isEqualTo(1);
    }

    @Test
    void timezoneChangesRecalculateAllHistoricalScheduleDates() {
        ZoneId previousZone = ZoneId.of("Pacific/Kiritimati");
        ZoneId nextZone = ZoneId.of("Pacific/Pago_Pago");
        long userId = authService.register("15100001016", "Abc12345", "Historical Timezone User", previousZone.getId());
        OffsetDateTime instant = OffsetDateTime.parse("2026-07-01T12:00:00Z");
        LocalDate previousDate = instant.atZoneSameInstant(previousZone).toLocalDate();
        LocalDate nextDate = instant.atZoneSameInstant(nextZone).toLocalDate();
        scheduleService.createSchedule(userId, Map.of(
                "title", "Historical timezone schedule",
                "timeType", "deadline_task",
                "deadlineTime", instant.toString(),
                "fatigueLevel", 4));
        long beforeRevision = fatigueService.currentDataRevision(userId);
        assertThat(decimal(fatigueService.daily(userId, previousDate), "plannedLoad")).isEqualByComparingTo("5");

        userService.updateTimezone(userId, nextZone.getId());

        Map<String, Object> previousSummary = jdbc.queryForMap("select timezone_snapshot,planned_load,data_revision from fatigue_daily_summary where user_id=? and local_date=?", userId, previousDate);
        Map<String, Object> nextSummary = jdbc.queryForMap("select timezone_snapshot,planned_load,data_revision from fatigue_daily_summary where user_id=? and local_date=?", userId, nextDate);
        assertThat(decimal(previousSummary.get("planned_load"))).isEqualByComparingTo("0");
        assertThat(decimal(nextSummary.get("planned_load"))).isEqualByComparingTo("5");
        assertThat(previousSummary).containsEntry("timezone_snapshot", nextZone.getId());
        assertThat(nextSummary).containsEntry("timezone_snapshot", nextZone.getId());
        assertThat(((Number) nextSummary.get("data_revision")).longValue()).isGreaterThan(beforeRevision);
    }

    @Test
    void recalibrationRefreshesPersistedHistoricalPredictions() {
        ZoneId zone = ZoneOffset.UTC;
        long userId = authService.register("15100001017", "Abc12345", "Historical Prediction User", zone.getId());
        LocalDate summaryDate = LocalDate.of(2026, 7, 15);
        scheduleService.createSchedule(userId, Map.of(
                "title", "Historical planned load",
                "timeType", "deadline_task",
                "deadlineTime", summaryDate.atTime(12, 0).toInstant(ZoneOffset.UTC).toString(),
                "fatigueLevel", 5));
        int beforePrediction = ((Number) fatigueService.daily(userId, summaryDate).get("predictedScore")).intValue();
        LocalDate trainingStart = LocalDate.of(2026, 7, 1);
        for (int index = 0; index < 7; index++) {
            jdbc.update("insert into fatigue_survey (user_id,local_date,timezone_snapshot,score,external_factor_level,external_factor_tags,completed_load_snapshot,weights_snapshot,capacity_before,model_eligible,learning_weight,completed_level_counts,algorithm_version) values (?,?,?,?,0,'[]',30,'{}',18,true,1,?,2)",
                    userId, trainingStart.plusDays(index), zone.getId(), 20, "{\"1\":0,\"2\":0,\"3\":0,\"4\":0,\"5\":1}");
        }

        Map<String, Object> profile = fatigueService.recalibrateModel(userId);
        int persistedPrediction = jdbc.queryForObject("select predicted_score from fatigue_daily_summary where user_id=? and local_date=?", Integer.class, userId, summaryDate);
        int currentPrediction = ((Number) fatigueService.daily(userId, summaryDate).get("predictedScore")).intValue();

        assertThat(decimal(profile.get("capacity75"))).isGreaterThan(BigDecimal.valueOf(18));
        assertThat(persistedPrediction).isLessThan(beforePrediction).isEqualTo(currentPrediction);
    }

    @Test
    void oversizedSurveyIntegersAreRejectedBeforeConversion() {
        long userId = register("15100001018", "Oversized Survey User");

        assertThatThrownBy(() -> fatigueService.submitSurvey(userId, Map.of("score", Long.MAX_VALUE)))
                .isInstanceOf(BusinessException.class)
                .extracting(ex -> ((BusinessException) ex).getCode())
                .isEqualTo(400);
    }

    private static <T> List<T> runConcurrently(Callable<T> operation) throws Exception {
        ExecutorService executor = Executors.newFixedThreadPool(2);
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        try {
            List<Future<T>> futures = List.of(
                    executor.submit(() -> { ready.countDown(); start.await(); return operation.call(); }),
                    executor.submit(() -> { ready.countDown(); start.await(); return operation.call(); }));
            assertThat(ready.await(5, TimeUnit.SECONDS)).isTrue();
            start.countDown();
            return List.of(futures.get(0).get(15, TimeUnit.SECONDS), futures.get(1).get(15, TimeUnit.SECONDS));
        } finally {
            executor.shutdownNow();
        }
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

    private static BigDecimal decimal(Map<String, Object> item, String key) {
        return decimal(item.get(key));
    }

    private static BigDecimal decimal(Object value) {
        return value == null ? BigDecimal.ZERO : new BigDecimal(String.valueOf(value));
    }

    private int count(String sql, Object... args) {
        Integer value = jdbc.queryForObject(sql, Integer.class, args);
        return value == null ? 0 : value;
    }
}
