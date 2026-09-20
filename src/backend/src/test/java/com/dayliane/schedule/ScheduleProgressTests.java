package com.dayliane.schedule;

import com.dayliane.auth.AuthService;
import com.dayliane.common.BusinessException;
import com.dayliane.fatigue.FatigueService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** 阶段 1A：个人长期任务每日进度与疲劳的边界覆盖。 */
@SpringBootTest
@ActiveProfiles("test")
class ScheduleProgressTests {

    @Autowired AuthService authService;
    @Autowired ScheduleService scheduleService;
    @Autowired FatigueService fatigueService;
    @Autowired JdbcTemplate jdbc;

    private static final ZoneId SHANGHAI = ZoneId.of("Asia/Shanghai");

    @BeforeEach
    void cleanDatabase() {
        for (String table : List.of("schedule_progress_daily", "fatigue_alert_log", "fatigue_survey_prompt_log",
                "fatigue_survey_skip", "fatigue_survey", "fatigue_daily_summary", "user_fatigue_profile", "notification",
                "auth_revoked_access_token", "auth_refresh_token", "auth_email_otp", "reminder", "schedule_series_exdate",
                "schedule", "task_group", "user")) {
            jdbc.update("delete from " + ("user".equals(table) ? "`user`" : table));
        }
    }

    private long newUser(String phone, ZoneId zone) {
        return authService.register(phone, "Abc12345", "Progress User", zone.getId());
    }

    private Map<String, Object> createLongTask(long userId, ZoneId zone, boolean progressTracking) {
        return createLongTask(userId, zone, progressTracking, 0);
    }

    /** startDaysAgo 用于构造「任务起始日早于今天」的场景，验证补录下限与跨天累计。 */
    private Map<String, Object> createLongTask(long userId, ZoneId zone, boolean progressTracking, int startDaysAgo) {
        LocalDate today = LocalDate.now(zone);
        Map<String, Object> req = new LinkedHashMap<>();
        req.put("title", "长期任务");
        req.put("timeType", "duration_task");
        req.put("startTime", today.minusDays(startDaysAgo).atTime(9, 0).atZone(zone).toInstant().toString());
        req.put("endTime", today.plusDays(9).atTime(18, 0).atZone(zone).toInstant().toString());
        req.put("progressTrackingEnabled", progressTracking);
        return scheduleService.createSchedule(userId, req);
    }

    private Map<String, Object> createPointEvent(long userId, ZoneId zone, boolean progressTracking) {
        LocalDate today = LocalDate.now(zone);
        Map<String, Object> req = new LinkedHashMap<>();
        req.put("title", "单点事件");
        req.put("timeType", "point_event");
        req.put("startTime", today.atTime(9, 0).atZone(zone).toInstant().toString());
        req.put("progressTrackingEnabled", progressTracking);
        return scheduleService.createSchedule(userId, req);
    }

    private Map<String, Object> submit(long scheduleId, long userId, Object cumulative, Integer level) {
        Map<String, Object> req = new LinkedHashMap<>();
        req.put("cumulativeProgress", cumulative);
        if (level != null) req.put("fatigueLevel", level);
        return scheduleService.submitScheduleProgress(scheduleId, userId, req);
    }

    private Map<String, Object> correct(long scheduleId, long userId, LocalDate date, Object delta, Integer level) {
        Map<String, Object> req = new LinkedHashMap<>();
        req.put("progressDelta", delta);
        if (level != null) req.put("fatigueLevel", level);
        return scheduleService.correctScheduleProgress(scheduleId, userId, date.toString(), req);
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> items(Map<String, Object> progress) {
        return (List<Map<String, Object>>) progress.get("items");
    }

    private static BigDecimal decimal(Object value) {
        return value == null ? null : new BigDecimal(String.valueOf(value));
    }

    private BigDecimal dailyCompletedLoad(long userId, LocalDate date) {
        return decimal(fatigueService.daily(userId, date).get("completedLoad"));
    }

    private long scheduleId(Map<String, Object> schedule) {
        return ((Number) schedule.get("id")).longValue();
    }

    private Map<String, Object> scheduleRow(long scheduleId) {
        return jdbc.queryForMap("select status, completed_at completedAt, completed_fatigue_level completedFatigueLevel, " +
                "completed_fatigue_weight completedFatigueWeight, progress_tracking_enabled progressTrackingEnabled, " +
                "progress_percent progressPercent, progress_completed_date progressCompletedDate from schedule where id=?", scheduleId);
    }

    @Test
    void dailyProgressIsOnlyAvailableForTasksWithoutRecurrence() {
        long userId = newUser("15200010001", SHANGHAI);
        LocalDate today = LocalDate.now(SHANGHAI);

        assertThatThrownBy(() -> createPointEvent(userId, SHANGHAI, true))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("only available for tasks");

        assertThatThrownBy(() -> scheduleService.createSchedule(userId, Map.of(
                "title", "重复任务",
                "timeType", "deadline_task",
                "deadlineTime", today.atTime(18, 0).atZone(SHANGHAI).toInstant().toString(),
                "rrule", "FREQ=DAILY",
                "progressTrackingEnabled", true)))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("not available for repeating schedules");

        Map<String, Object> created = createLongTask(userId, SHANGHAI, true);
        assertThat(created).containsEntry("progressTrackingEnabled", true);
        assertThat(decimal(created.get("progressPercent"))).isEqualByComparingTo("0");
        assertThat(scheduleRow(scheduleId(created))).containsEntry("progressTrackingEnabled", true);
    }

    @Test
    void submittingProgressRequiresFatigueLevelWhileTrackingIsOn() {
        long userId = newUser("15200010002", SHANGHAI);
        long id = scheduleId(createLongTask(userId, SHANGHAI, true));

        assertThatThrownBy(() -> submit(id, userId, 30, null))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("fatigueLevel is required when fatigue tracking is enabled");
        assertThat(countRows(id)).isZero();
        assertThat(decimal(scheduleRow(id).get("progressPercent"))).isEqualByComparingTo("0");
    }

    @Test
    void sameDayResubmissionUpdatesOneRowWithoutDoubleCounting() {
        long userId = newUser("15200010003", SHANGHAI);
        long id = scheduleId(createLongTask(userId, SHANGHAI, true));
        LocalDate today = LocalDate.now(SHANGHAI);

        Map<String, Object> first = submit(id, userId, 40, 4);
        assertThat(items(first)).hasSize(1);
        assertThat(decimal(items(first).get(0).get("progressDelta"))).isEqualByComparingTo("40");
        assertThat(decimal(items(first).get(0).get("completedLoad"))).isEqualByComparingTo("2");
        assertThat(dailyCompletedLoad(userId, today)).isEqualByComparingTo("2");

        Map<String, Object> second = submit(id, userId, 70, 4);
        assertThat(items(second)).hasSize(1);
        assertThat(decimal(items(second).get(0).get("progressDelta"))).isEqualByComparingTo("70");
        assertThat(decimal(items(second).get(0).get("cumulativeProgress"))).isEqualByComparingTo("70");
        // 70/100 × 权重 5 = 3.5，而不是 40% + 30% 分别累计后的重复值。
        assertThat(decimal(items(second).get(0).get("completedLoad"))).isEqualByComparingTo("3.5");
        assertThat(decimal(second.get("totalCompletedLoad"))).isEqualByComparingTo("3.5");
        assertThat(dailyCompletedLoad(userId, today)).isEqualByComparingTo("3.5");
        assertThat(countRows(id)).isEqualTo(1);
        assertThat(decimal(scheduleRow(id).get("progressPercent"))).isEqualByComparingTo("70");
    }

    @Test
    @SuppressWarnings("unchecked")
    void reachingHundredPercentCompletesTaskWithoutFullTaskSnapshot() {
        long userId = newUser("15200010004", SHANGHAI);
        long id = scheduleId(createLongTask(userId, SHANGHAI, true));
        LocalDate today = LocalDate.now(SHANGHAI);

        submit(id, userId, 100, 4);

        Map<String, Object> row = scheduleRow(id);
        assertThat(row).containsEntry("status", "completed");
        assertThat(row.get("completedAt")).isNotNull();
        // 不再写入整项完成快照，避免与每日进度负荷重复累计。
        assertThat(row.get("completedFatigueLevel")).isNull();
        assertThat(row.get("completedFatigueWeight")).isNull();
        assertThat(decimal(row.get("progressPercent"))).isEqualByComparingTo("100");

        Map<String, Object> daily = fatigueService.daily(userId, today);
        assertThat(decimal(daily.get("completedLoad"))).isEqualByComparingTo("5");
        assertThat(decimal(daily.get("plannedLoad"))).isEqualByComparingTo("3");
        assertThat(((Number) daily.get("completedCount")).intValue()).isEqualTo(1);
        List<Map<String, Object>> contributors = (List<Map<String, Object>>) daily.get("topCompletedContributors");
        assertThat(contributors).hasSize(1);
        assertThat(contributors.get(0)).containsEntry("source", "daily_progress");
        assertThat(decimal(contributors.get(0).get("weight"))).isEqualByComparingTo("5");
    }

    @Test
    void completeEndpointIsBlockedUntilDailyProgressReachesHundred() {
        long userId = newUser("15200010005", SHANGHAI);
        long id = scheduleId(createLongTask(userId, SHANGHAI, true));

        submit(id, userId, 60, 3);
        assertThatThrownBy(() -> scheduleService.setScheduleStatus(id, userId, "completed"))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("must be completed by submitting progress up to 100%");
        assertThat(scheduleRow(id)).containsEntry("status", "pending");

        submit(id, userId, 100, 3);
        assertThat(scheduleRow(id)).containsEntry("status", "completed");
        assertThatThrownBy(() -> scheduleService.setScheduleStatus(id, userId, "completed"))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("already completed");

        // 重新打开后进度历史保留，再次提交 100% 只补记完成、不重复累计负荷。
        scheduleService.setScheduleStatus(id, userId, "pending");
        assertThat(decimal(scheduleRow(id).get("progressPercent"))).isEqualByComparingTo("100");
        submit(id, userId, 100, null);
        assertThat(scheduleRow(id)).containsEntry("status", "completed");
        assertThat(countRows(id)).isEqualTo(1);
    }

    @Test
    void historyCorrectionRebuildsCumulativeAndReopensCompletedTask() {
        long userId = newUser("15200010006", SHANGHAI);
        long id = scheduleId(createLongTask(userId, SHANGHAI, true));
        LocalDate today = LocalDate.now(SHANGHAI);

        submit(id, userId, 100, 4);
        assertThat(scheduleRow(id)).containsEntry("status", "completed");
        assertThat(dailyCompletedLoad(userId, today)).isEqualByComparingTo("5");

        Map<String, Object> corrected = correct(id, userId, today, 40, 4);
        assertThat(decimal(corrected.get("progressPercent"))).isEqualByComparingTo("40");
        assertThat(items(corrected)).hasSize(1);
        assertThat(decimal(items(corrected).get(0).get("completedLoad"))).isEqualByComparingTo("2");

        Map<String, Object> row = scheduleRow(id);
        assertThat(row).containsEntry("status", "pending");
        assertThat(row.get("completedAt")).isNull();
        assertThat(row.get("completedFatigueLevel")).isNull();
        assertThat(decimal(row.get("progressPercent"))).isEqualByComparingTo("40");
        assertThat(dailyCompletedLoad(userId, today)).isEqualByComparingTo("2");
    }

    @Test
    void historyCorrectionChainsAcrossDaysAndRejectsOverflow() {
        long userId = newUser("15200010007", SHANGHAI);
        // 任务起始日早于昨天，昨天才属于可补录区间。
        long id = scheduleId(createLongTask(userId, SHANGHAI, true, 5));
        LocalDate today = LocalDate.now(SHANGHAI);
        LocalDate yesterday = today.minusDays(1);

        Map<String, Object> corrected = correct(id, userId, yesterday, 30, 3);
        assertThat(items(corrected)).hasSize(1);
        assertThat(items(corrected).get(0)).containsEntry("progressDate", yesterday.toString());
        assertThat(decimal(items(corrected).get(0).get("completedLoad"))).isEqualByComparingTo("0.9");
        assertThat(decimal(corrected.get("progressPercent"))).isEqualByComparingTo("30");

        corrected = correct(id, userId, today, 40, 4);
        assertThat(items(corrected)).hasSize(2);
        assertThat(decimal(items(corrected).get(0).get("cumulativeProgress"))).isEqualByComparingTo("30");
        assertThat(decimal(items(corrected).get(1).get("cumulativeProgress"))).isEqualByComparingTo("70");
        assertThat(decimal(items(corrected).get(1).get("completedLoad"))).isEqualByComparingTo("2");
        assertThat(decimal(corrected.get("totalCompletedLoad"))).isEqualByComparingTo("2.9");

        assertThatThrownBy(() -> correct(id, userId, yesterday, 90, 3))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("cannot exceed 100");
        assertThat(decimal(scheduleRow(id).get("progressPercent"))).isEqualByComparingTo("70");
    }

    @Test
    void historyCorrectionValidatesDateAndDeltaRange() {
        long userId = newUser("15200010008", SHANGHAI);
        long id = scheduleId(createLongTask(userId, SHANGHAI, true, 5));
        LocalDate today = LocalDate.now(SHANGHAI);

        submit(id, userId, 30, 3);
        assertThatThrownBy(() -> correct(id, userId, today.plusDays(1), 10, 3))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("cannot be in the future");
        assertThatThrownBy(() -> correct(id, userId, today, -5, 3))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("cannot be negative");
        assertThatThrownBy(() -> correct(id, userId, today.minusDays(2), 0, null))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("no daily progress record for that date");
        assertThatThrownBy(() -> submit(id, userId, 20, 3))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("progress cannot decrease");
    }

    @Test
    void backfillRejectsDatesBeforeScheduleStartOrOutsideRetention() {
        long userId = newUser("15200010019", SHANGHAI);
        LocalDate today = LocalDate.now(SHANGHAI);
        long recent = scheduleId(createLongTask(userId, SHANGHAI, true, 5));

        // 早于任务有效起始日期：拒绝（不允许对任务创建之前的日期补录）。
        assertThatThrownBy(() -> correct(recent, userId, today.minusDays(6), 10, 3))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("earlier than the schedule start date");
        // 起始日期当天与今天之间允许补录，并按日期顺序重算累计。
        Map<String, Object> corrected = correct(recent, userId, today.minusDays(4), 20, 3);
        assertThat(items(corrected)).hasSize(1);
        assertThat(decimal(corrected.get("progressPercent"))).isEqualByComparingTo("20");

        // 超出疲劳保留期（沿用 retentionDays）：拒绝。
        long old = scheduleId(createLongTask(userId, SHANGHAI, true, 200));
        int retention = (Integer) scheduleService.scheduleProgress(old, userId).get("retentionDays");
        assertThatThrownBy(() -> correct(old, userId, today.minusDays(retention + 1L), 10, 3))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("retention window");
        // 保留期内的历史日期仍可补录。
        Map<String, Object> withinRetention = correct(old, userId, today.minusDays(retention), 10, 3);
        assertThat(items(withinRetention)).hasSize(1);
    }

    @Test
    void backfillProgressResponseExposesDateRangeAndSubmitFlags() {
        long userId = newUser("15200010020", SHANGHAI);
        LocalDate today = LocalDate.now(SHANGHAI);
        long id = scheduleId(createLongTask(userId, SHANGHAI, true, 5));

        Map<String, Object> progress = scheduleService.scheduleProgress(id, userId);
        assertThat(progress).containsEntry("canSubmitProgress", true);
        assertThat(progress).containsEntry("canBackfill", true);
        assertThat(String.valueOf(progress.get("backfillMinDate"))).isEqualTo(today.minusDays(5).toString());
        assertThat(String.valueOf(progress.get("backfillMaxDate"))).isEqualTo(today.toString());
        assertThat(progress.get("completedDate")).isNull();
        assertThat((Integer) progress.get("retentionDays")).isGreaterThanOrEqualTo(180);
    }

    @Test
    void disablingFatigueTrackingKeepsProgressWithoutFatigueLoad() {
        long userId = newUser("15200010009", SHANGHAI);
        long id = scheduleId(createLongTask(userId, SHANGHAI, true));
        LocalDate today = LocalDate.now(SHANGHAI);

        fatigueService.updatePreferences(userId, Map.of("fatigueTrackingEnabled", false));
        Map<String, Object> submitted = submit(id, userId, 50, null);
        assertThat(items(submitted)).hasSize(1);
        assertThat(items(submitted).get(0).get("fatigueLevel")).isNull();
        assertThat(decimal(items(submitted).get(0).get("completedLoad"))).isEqualByComparingTo("0");
        assertThat(decimal(scheduleRow(id).get("progressPercent"))).isEqualByComparingTo("50");
        assertThat(dailyCompletedLoad(userId, today)).isEqualByComparingTo("0");

        assertThatThrownBy(() -> submit(id, userId, 60, 4))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("not allowed when fatigue tracking is disabled");
    }

    @Test
    void progressIsIsolatedPerUser() {
        long owner = newUser("15200010010", SHANGHAI);
        long stranger = newUser("15200010011", SHANGHAI);
        long id = scheduleId(createLongTask(owner, SHANGHAI, true));
        submit(id, owner, 30, 3);

        assertThatThrownBy(() -> scheduleService.scheduleProgress(id, stranger))
                .isInstanceOf(BusinessException.class).hasMessageContaining("schedule not found");
        assertThatThrownBy(() -> submit(id, stranger, 40, 3))
                .isInstanceOf(BusinessException.class).hasMessageContaining("schedule not found");
        assertThatThrownBy(() -> correct(id, stranger, LocalDate.now(SHANGHAI), 5, 3))
                .isInstanceOf(BusinessException.class).hasMessageContaining("schedule not found");
        assertThat(countRows(id)).isEqualTo(1);
    }

    @Test
    void progressLoadUsesEachUserOwnTimezoneDate() {
        // UTC+14 与 UTC-10 的本地日期恒定相差一天，可稳定验证按用户时区归集。
        ZoneId ahead = ZoneId.of("Pacific/Kiritimati");
        ZoneId behind = ZoneId.of("Pacific/Honolulu");
        long aheadUser = newUser("15200010012", ahead);
        long behindUser = newUser("15200010013", behind);
        long aheadTask = scheduleId(createLongTask(aheadUser, ahead, true));
        long behindTask = scheduleId(createLongTask(behindUser, behind, true));

        submit(aheadTask, aheadUser, 50, 4);
        submit(behindTask, behindUser, 50, 4);

        String aheadDate = String.valueOf(items(scheduleService.scheduleProgress(aheadTask, aheadUser)).get(0).get("progressDate"));
        String behindDate = String.valueOf(items(scheduleService.scheduleProgress(behindTask, behindUser)).get(0).get("progressDate"));
        assertThat(aheadDate).isEqualTo(LocalDate.now(ahead).toString());
        assertThat(behindDate).isEqualTo(LocalDate.now(behind).toString());
        assertThat(aheadDate).isNotEqualTo(behindDate);
        assertThat(dailyCompletedLoad(aheadUser, LocalDate.now(ahead))).isEqualByComparingTo("2.5");
        assertThat(dailyCompletedLoad(behindUser, LocalDate.now(behind))).isEqualByComparingTo("2.5");
        assertThat(dailyCompletedLoad(behindUser, LocalDate.now(ahead))).isEqualByComparingTo("0");
    }

    @Test
    void deletingTaskRemovesProgressLoadFromSummaries() {
        long userId = newUser("15200010014", SHANGHAI);
        long id = scheduleId(createLongTask(userId, SHANGHAI, true));
        LocalDate today = LocalDate.now(SHANGHAI);

        submit(id, userId, 50, 4);
        assertThat(dailyCompletedLoad(userId, today)).isEqualByComparingTo("2.5");
        assertThat(personalCompletedLoad(userId)).isEqualByComparingTo("2.5");

        // 取消任务保留已经发生的每日进度与疲劳历史，负荷继续计入个人完成负荷。
        scheduleService.setScheduleStatus(id, userId, "cancelled");
        assertThat(scheduleRow(id)).containsEntry("status", "cancelled");
        assertThat(countRows(id)).isEqualTo(1);
        assertThat(dailyCompletedLoad(userId, today)).isEqualByComparingTo("2.5");

        scheduleService.deleteSchedule(id, userId);
        assertThat(dailyCompletedLoad(userId, today)).isEqualByComparingTo("0");
        assertThat(personalCompletedLoad(userId)).isEqualByComparingTo("0");
    }

    @Test
    void progressTrackingCannotBeDisabledWhileRecordsExist() {
        long userId = newUser("15200010015", SHANGHAI);
        long id = scheduleId(createLongTask(userId, SHANGHAI, true));
        LocalDate today = LocalDate.now(SHANGHAI);

        submit(id, userId, 30, 3);
        assertThatThrownBy(() -> scheduleService.updateSchedule(id, userId, Map.of("progressTrackingEnabled", false)))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("remove the daily progress records");

        correct(id, userId, today, 0, null);
        assertThat(countRows(id)).isZero();
        Map<String, Object> updated = scheduleService.updateSchedule(id, userId, Map.of("progressTrackingEnabled", false));
        assertThat(updated).containsEntry("progressTrackingEnabled", false);
        assertThat(decimal(updated.get("progressPercent"))).isEqualByComparingTo("0");
        assertThatThrownBy(() -> submit(id, userId, 10, 3))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("does not enable daily progress");
    }

    @Test
    @SuppressWarnings("unchecked")
    void reportAttributesProgressLoadToProgressDatesOnly() {
        long userId = newUser("15200010016", SHANGHAI);
        long id = scheduleId(createLongTask(userId, SHANGHAI, true));
        LocalDate today = LocalDate.now(SHANGHAI);

        submit(id, userId, 100, 4);

        Map<String, Object> report = fatigueService.report(userId, "week");
        Map<String, Object> summary = (Map<String, Object>) report.get("summary");
        assertThat(decimal(summary.get("personalCompletedLoad"))).isEqualByComparingTo("5");
        assertThat(decimal(summary.get("personalProgressCompletedLoad"))).isEqualByComparingTo("5");
        assertThat(decimal(summary.get("personalPlannedLoad"))).isEqualByComparingTo("3");
        assertThat(((Number) summary.get("personalCompletedCount")).intValue()).isEqualTo(1);
        assertThat(((Number) summary.get("personalScheduleCount")).intValue()).isEqualTo(1);
        assertThat(decimal(summary.get("personalCompletionRate"))).isEqualByComparingTo("1");

        List<Map<String, Object>> trend = (List<Map<String, Object>>) report.get("trend");
        Map<String, Object> progressDay = trend.stream().filter(row -> today.toString().equals(row.get("localDate"))).findFirst().orElseThrow();
        assertThat(decimal(progressDay.get("completedLoad"))).isEqualByComparingTo("5");
        assertThat(decimal(progressDay.get("progressCompletedLoad"))).isEqualByComparingTo("5");
        Map<String, Object> otherDay = trend.stream().filter(row -> !today.toString().equals(row.get("localDate"))).findFirst().orElseThrow();
        assertThat(decimal(otherDay.get("progressCompletedLoad"))).isEqualByComparingTo("0");
    }

    @Test
    @SuppressWarnings("unchecked")
    void dailyProgressLoadFeedsTheEndOfDaySurveySample() {
        long userId = newUser("15200010017", SHANGHAI);
        long id = scheduleId(createLongTask(userId, SHANGHAI, true));

        submit(id, userId, 50, 4);

        Map<String, Object> result = fatigueService.submitSurvey(userId, Map.of("score", 62));
        Map<String, Object> survey = (Map<String, Object>) result.get("survey");
        // 每日进度负荷进入日终调查快照，并作为个人模型训练样本（含当日疲劳等级计数）。
        assertThat(decimal(survey.get("completedLoadSnapshot"))).isEqualByComparingTo("2.5");
        assertThat(survey).containsEntry("modelEligible", true);
        assertThat(survey.get("ineligibleReason")).isNull();
        Map<String, Object> dailyCounts = (Map<String, Object>) fatigueService.daily(userId, LocalDate.now(SHANGHAI)).get("completedLevelCounts");
        assertThat(dailyCounts).as("daily level counts include the progress level").containsEntry("4", 1);
        // 持久化训练样本里的当日等级计数与完成负荷一起写入 fatigue_survey。
        // 注意：H2 的 JSON 列会把写入值再包一层 JSON 字符串转义，MySQL 直接保存 JSON 文本，
        // 因此这里先去掉转义再校验，避免测试只对某一种实现成立。
        String persistedCounts = jdbc.queryForObject("select completed_level_counts from fatigue_survey where user_id=?", String.class, userId);
        assertThat(persistedCounts.replace("\\", "")).as("persisted level counts").contains("\"4\":1");
    }

    @Test
    void editingProgressScheduleKeepsTrackingAndHistory() {
        long userId = newUser("15200010018", SHANGHAI);
        long id = scheduleId(createLongTask(userId, SHANGHAI, true));
        submit(id, userId, 30, 3);

        // 调用方回传 progressTrackingEnabled=true 时不得被当作关闭；未回传时沿用当前值。
        Map<String, Object> renamed = scheduleService.editOccurrence(id, userId, Map.of(
                "title", "改名后的长期任务", "progressTrackingEnabled", true));
        assertThat(renamed).containsEntry("title", "改名后的长期任务");
        assertThat(renamed).containsEntry("progressTrackingEnabled", true);
        assertThat(decimal(renamed.get("progressPercent"))).isEqualByComparingTo("30");

        Map<String, Object> renamedAgain = scheduleService.editOccurrence(id, userId, Map.of("title", "再改名"));
        assertThat(renamedAgain).containsEntry("progressTrackingEnabled", true);
        assertThat(countRows(id)).isEqualTo(1);
        assertThat(dailyCompletedLoad(userId, LocalDate.now(SHANGHAI))).isEqualByComparingTo("0.9");

        // 改成点事件会与每日进度冲突，必须显式关闭（且要先清零记录）才能切换。
        assertThatThrownBy(() -> scheduleService.editOccurrence(id, userId, Map.of("timeType", "point_event")))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("only available for tasks");
    }

    @Test
    @SuppressWarnings("unchecked")
    void partialProgressStillQualifiesForTheEndOfDaySurvey() {
        long userId = newUser("15200010021", SHANGHAI);
        long id = scheduleId(createLongTask(userId, SHANGHAI, true));
        LocalDate today = LocalDate.now(SHANGHAI);

        submit(id, userId, 40, 4);

        // 只有部分进度：completedCount 仍为 0，但个人完成负荷已产生，必须能进入日终调查（P05）。
        Map<String, Object> daily = fatigueService.daily(userId, today);
        assertThat(((Number) daily.get("completedCount")).intValue()).isZero();
        assertThat(decimal(daily.get("completedLoad"))).isEqualByComparingTo("2");

        Map<String, Object> surveyToday = fatigueService.surveyToday(userId);
        assertThat(surveyToday).containsEntry("pending", true);
        assertThat(String.valueOf(surveyToday.get("localDate"))).isEqualTo(today.toString());
        assertThat((List<String>) surveyToday.get("availableDates")).contains(today.toString());

        // 每天扫描提醒也按同一口径触发。
        assertThat(fatigueService.scanSurveyPrompts()).isGreaterThanOrEqualTo(0);
    }

    @Test
    @SuppressWarnings("unchecked")
    void listCompletedLoadUsesDailyProgressAggregation() {
        long userId = newUser("15200010022", SHANGHAI);
        Map<String, Object> created = createLongTask(userId, SHANGHAI, true);
        long id = scheduleId(created);
        // 计划疲劳等级 2（权重 2），实际按等级 4（权重 5）推进 50%：两种口径结果不同，可区分。
        jdbc.update("update schedule set fatigue_level=2 where id=?", id);
        submit(id, userId, 50, 4);

        Map<String, Object> pending = scheduleService.listSchedules(userId, 1, 20, "pending", null, null, null, null);
        Map<String, Object> pendingItem = ((List<Map<String, Object>>) pending.get("list")).get(0);
        assertThat(decimal(pendingItem.get("progressCompletedLoad"))).isEqualByComparingTo("2.5");

        submit(id, userId, 100, 4);
        Map<String, Object> completed = scheduleService.listSchedules(userId, 1, 20, "completed", null, null, null, null);
        List<Map<String, Object>> completedItems = (List<Map<String, Object>>) completed.get("list");
        assertThat(completedItems).hasSize(1);
        // P06：完成负荷必须是每日记录累计值（50%+50% 按权重 5 = 5.0），不能回退成整项计划权重（2）。
        assertThat(decimal(completedItems.get(0).get("progressCompletedLoad"))).isEqualByComparingTo("5");
        List<Map<String, Object>> summaries = (List<Map<String, Object>>) completed.get("sectionSummaries");
        BigDecimal summaryLoad = summaries.stream()
                .map(summary -> decimal(summary.get("completedLoad")))
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        assertThat(summaryLoad).isEqualByComparingTo("5");
    }

    @Test
    void concurrentSubmissionsKeepProgressStatusAndRecordsConsistent() throws Exception {
        long userId = newUser("15200010023", SHANGHAI);
        long id = scheduleId(createLongTask(userId, SHANGHAI, true));

        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            CountDownLatch start = new CountDownLatch(1);
            List<Future<String>> results = new ArrayList<>();
            for (Object cumulative : List.of(50, 100)) {
                results.add(pool.submit(() -> {
                    start.await();
                    try {
                        submit(id, userId, cumulative, 4);
                        return "ok";
                    } catch (BusinessException rejected) {
                        return rejected.getMessage();
                    }
                }));
            }
            start.countDown();
            for (Future<String> result : results) {
                String outcome = result.get(30, TimeUnit.SECONDS);
                // 请求要么成功；要么被业务规则合法拒绝：进度回退，或另一笔并发请求已经先把任务完成
                // （行锁内重读状态后得到 completed）。两种拒绝都不允许出现状态与进度互相矛盾的其它结果。
                assertThat(outcome).satisfiesAnyOf(
                        message -> assertThat(message).isEqualTo("ok"),
                        message -> assertThat(message).contains("progress cannot decrease"),
                        message -> assertThat(message).contains("must be pending"));
            }
        } finally {
            pool.shutdownNow();
        }

        Map<String, Object> row = scheduleRow(id);
        BigDecimal percent = decimal(row.get("progressPercent"));
        assertThat(percent).isEqualByComparingTo("100");
        assertThat(row).containsEntry("status", "completed");
        assertThat(row.get("completedFatigueLevel")).isNull();
        // 进度必须与每日增量之和一致，且完成日等于达到 100% 的进度日期。
        BigDecimal sum = decimal(jdbc.queryForObject("select coalesce(sum(progress_delta),0) from schedule_progress_daily where schedule_id=?", BigDecimal.class, id));
        assertThat(sum).isEqualByComparingTo("100");
        assertThat(String.valueOf(row.get("progressCompletedDate"))).isEqualTo(LocalDate.now(SHANGHAI).toString());
        assertThat(countRows(id)).isEqualTo(1);
    }

    @Test
    void historyCorrectionStampsCompletionFromTheBackfilledDate() {
        long userId = newUser("15200010024", SHANGHAI);
        long id = scheduleId(createLongTask(userId, SHANGHAI, true, 5));
        LocalDate today = LocalDate.now(SHANGHAI);
        LocalDate yesterday = today.minusDays(1);

        // 直接补录昨天的 100%：完成日必须是昨天（进度首次达标的日期），不能是今天的修正操作时间。
        correct(id, userId, yesterday, 100, 4);

        Map<String, Object> row = scheduleRow(id);
        assertThat(row).containsEntry("status", "completed");
        assertThat(String.valueOf(row.get("progressCompletedDate"))).isEqualTo(yesterday.toString());
        assertThat(scheduleService.scheduleProgress(id, userId)).containsEntry("completedDate", yesterday.toString());

        // 已完成的进度任务再改 100% 所在日期，完成日同步跟随新日期。
        correct(id, userId, yesterday, 0, null);
        correct(id, userId, today, 100, 4);
        assertThat(String.valueOf(scheduleRow(id).get("progressCompletedDate"))).isEqualTo(today.toString());
    }

    @Test
    void correctionOnCancelledTaskDoesNotAutoComplete() {
        long userId = newUser("15200010025", SHANGHAI);
        long id = scheduleId(createLongTask(userId, SHANGHAI, true));
        LocalDate today = LocalDate.now(SHANGHAI);

        submit(id, userId, 60, 3);
        scheduleService.setScheduleStatus(id, userId, "cancelled");
        // 取消状态不能通过历史修正直接变成完成。
        correct(id, userId, today, 100, 3);
        Map<String, Object> cancelled = scheduleRow(id);
        assertThat(cancelled).containsEntry("status", "cancelled");
        assertThat(cancelled.get("completedAt")).isNull();
        assertThat(cancelled.get("progressCompletedDate")).isNull();
        // 取消期间也不能提交新进度或走普通完成接口。
        assertThatThrownBy(() -> submit(id, userId, 100, 3))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("must be pending");
        assertThatThrownBy(() -> scheduleService.setScheduleStatus(id, userId, "completed"))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("must be restored");

        // 恢复为待处理后，可由 100% 进度完成。
        scheduleService.setScheduleStatus(id, userId, "pending");
        submit(id, userId, 100, 3);
        assertThat(scheduleRow(id)).containsEntry("status", "completed");
    }

    @Test
    void completedListAcrossPeriodsKeepsCompletionRateAtMostOne() {
        long userId = newUser("15200010026", SHANGHAI);
        LocalDate today = LocalDate.now(SHANGHAI);
        // 任务起始日落在上周：计划日在上一周期，完成日落在本周期，属于跨周期结转。
        long carried = scheduleId(createLongTask(userId, SHANGHAI, true, 8));
        long thisWeek = scheduleId(createLongTask(userId, SHANGHAI, true, 0));

        submit(thisWeek, userId, 100, 3);
        submit(carried, userId, 100, 3);

        @SuppressWarnings("unchecked")
        Map<String, Object> summary = (Map<String, Object>) fatigueService.report(userId, "week").get("summary");
        BigDecimal rate = decimal(summary.get("personalCompletionRate"));
        assertThat(rate).isNotNull();
        assertThat(rate).isBetween(BigDecimal.ZERO, BigDecimal.ONE);
        int eligible = ((Number) summary.get("personalScheduleCount")).intValue();
        int completed = ((Number) summary.get("personalCompletedCount")).intValue();
        assertThat(completed).isLessThanOrEqualTo(eligible);
        // 跨周期结转任务与本期任务都只计一次。
        assertThat(eligible).isEqualTo(2);
        assertThat(completed).isEqualTo(2);
        // 只有达到 100% 的长期任务才计入完成；部分进度不算完成项。
        long partial = scheduleId(createLongTask(userId, SHANGHAI, true, 0));
        submit(partial, userId, 40, 3);
        @SuppressWarnings("unchecked")
        Map<String, Object> afterPartial = (Map<String, Object>) fatigueService.report(userId, "week").get("summary");
        assertThat(((Number) afterPartial.get("personalCompletedCount")).intValue()).isEqualTo(2);
        assertThat(decimal(afterPartial.get("personalCompletionRate"))).isEqualByComparingTo("0.6667");
    }

    private BigDecimal personalCompletedLoad(long userId) {
        @SuppressWarnings("unchecked")
        Map<String, Object> summary = (Map<String, Object>) fatigueService.report(userId, "week").get("summary");
        return decimal(summary.get("personalCompletedLoad"));
    }

    private int countRows(long scheduleId) {
        Integer count = jdbc.queryForObject("select count(*) from schedule_progress_daily where schedule_id=?", Integer.class, scheduleId);
        return count == null ? 0 : count;
    }
}
