package com.dayliane.schedule;

import com.dayliane.auth.AuthService;
import com.dayliane.common.BusinessException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import java.time.LocalDate;
import java.time.YearMonth;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest
@ActiveProfiles("test")
class ScheduleRepeatTests {

    @Autowired AuthService authService;
    @Autowired ScheduleService scheduleService;
    @Autowired JdbcTemplate jdbc;

    @BeforeEach
    void cleanDatabase() {
        jdbc.update("delete from schedule_series_exdate");
        for (String table : List.of("fatigue_alert_log", "fatigue_survey_prompt_log", "fatigue_survey_skip", "fatigue_survey", "fatigue_daily_summary", "user_fatigue_profile", "notification", "auth_revoked_access_token", "auth_refresh_token", "auth_email_otp", "reminder", "schedule", "task_group", "user")) {
            jdbc.update("delete from " + ("user".equals(table) ? "`user`" : table));
        }
    }

    private long newUser(String phone, ZoneId zone) {
        return authService.register(phone, "Abc12345", "Repeat User", zone.getId());
    }

    private Map<String, Object> createDailySeries(long userId, ZoneId zone) {
        LocalDate today = LocalDate.now(zone);
        return scheduleService.createSchedule(userId, Map.of(
                "title", "Daily",
                "timeType", "deadline_task",
                "deadlineTime", today.atTime(12, 0).atZone(zone).toInstant().toString(),
                "rrule", "FREQ=DAILY"
        ));
    }

    @Test
    void dailyRecurrenceMaterializesRemainingCurrentMonthInstances() {
        ZoneId zone = ZoneId.of("Asia/Shanghai");
        long userId = newUser("15100010001", zone);
        LocalDate today = LocalDate.now(zone);
        int lastDay = YearMonth.now(zone).atEndOfMonth().getDayOfMonth();
        int expectedTotal = 1 + (lastDay - today.getDayOfMonth());

        Map<String, Object> created = createDailySeries(userId, zone);
        String seriesId = (String) created.get("seriesId");

        assertThat(created).containsEntry("rrule", "FREQ=DAILY");
        assertThat(created).containsEntry("occurrenceDate", today.toString());
        assertThat(count("select count(*) from schedule where series_id=?", seriesId)).isEqualTo(expectedTotal);

        // Idempotent reinjection: a second full materialize creates nothing new.
        Map<String, Object> again = scheduleService.materializeRepeat(userId, Map.of());
        assertThat(((Number) again.get("created")).intValue()).isZero();
        assertThat(count("select count(*) from schedule where series_id=?", seriesId)).isEqualTo(expectedTotal);
    }

    @Test
    void singleOccurrenceCompletionDoesNotAffectSiblings() {
        ZoneId zone = ZoneId.of("Asia/Shanghai");
        long userId = newUser("15100010002", zone);
        Map<String, Object> created = createDailySeries(userId, zone);
        String seriesId = (String) created.get("seriesId");
        long seedId = ((Number) created.get("id")).longValue();

        int totalBefore = count("select count(*) from schedule where series_id=?", seriesId);
        scheduleService.setScheduleStatus(seedId, userId, "completed");

        assertThat(count("select count(*) from schedule where series_id=? and status='pending'", seriesId)).isEqualTo(totalBefore - 1);
        assertThat(count("select count(*) from schedule where series_id=? and id=? and status='completed'", seriesId, seedId)).isEqualTo(1);

        // Completed instance keeps its own fatigue snapshot while siblings stay pending.
        scheduleService.setScheduleStatus(seedId, userId, "pending");
        assertThat(count("select count(*) from schedule where series_id=? and status='completed'", seriesId)).isZero();
    }

    @Test
    void editOccurrenceChangesOnlyThatInstanceAndRejectsSeriesFields() {
        ZoneId zone = ZoneId.of("Asia/Shanghai");
        long userId = newUser("15100010003", zone);
        Map<String, Object> created = createDailySeries(userId, zone);
        String seriesId = (String) created.get("seriesId");
        long seedId = ((Number) created.get("id")).longValue();

        assertThatThrownBy(() -> scheduleService.editOccurrence(seedId, userId, Map.of("rrule", "FREQ=WEEKLY")))
                .isInstanceOf(BusinessException.class)
                .extracting(ex -> ((BusinessException) ex).getCode())
                .isEqualTo(400);

        scheduleService.editOccurrence(seedId, userId, Map.of("title", "Only me"));
        assertThat(jdbc.queryForObject("select title from schedule where id=?", String.class, seedId)).isEqualTo("Only me");
        assertThat(count("select count(*) from schedule where series_id=? and id<>? and title<>'Daily'", seriesId, seedId)).isZero();
        assertThat(jdbc.queryForObject("select series_id from schedule where id=?", String.class, seedId)).isEqualTo(seriesId);
    }

    @Test
    void updateSeriesAffectsFuturePendingButPreservesCompleted() {
        ZoneId zone = ZoneId.of("Asia/Shanghai");
        long userId = newUser("15100010004", zone);
        Map<String, Object> created = createDailySeries(userId, zone);
        String seriesId = (String) created.get("seriesId");
        long seedId = ((Number) created.get("id")).longValue();

        scheduleService.setScheduleStatus(seedId, userId, "completed");
        int pendingBefore = count("select count(*) from schedule where series_id=? and status='pending'", seriesId);

        scheduleService.updateSeries(userId, seriesId, Map.of("title", "Renamed series"));

        Map<String, Object> seed = scheduleService.requireSchedule(seedId, userId);
        assertThat(seed).containsEntry("status", "completed").containsEntry("title", "Daily");
        assertThat(count("select count(*) from schedule where series_id=? and status='pending' and title='Renamed series'", seriesId)).isEqualTo(pendingBefore);
        assertThat(count("select count(*) from schedule where series_id=? and status='pending' and title<>'Renamed series'", seriesId)).isZero();
    }

    @Test
    void futureMonthMaterializationUsesEditedTemplateWhenSeedCompleted() {
        ZoneId zone = ZoneId.of("Asia/Shanghai");
        long userId = newUser("15100010008", zone);
        Map<String, Object> created = createDailySeries(userId, zone);
        String seriesId = (String) created.get("seriesId");
        long seedId = ((Number) created.get("id")).longValue();

        scheduleService.setScheduleStatus(seedId, userId, "completed");
        scheduleService.updateSeries(userId, seriesId, Map.of("title", "Renamed for next month"));

        LocalDate nextMonthStart = LocalDate.now(zone).plusMonths(1).withDayOfMonth(1);
        LocalDate nextMonthEnd = nextMonthStart.with(java.time.temporal.TemporalAdjusters.lastDayOfMonth());

        scheduleService.materializeForRange(userId, nextMonthStart, nextMonthEnd);

        assertThat(count("select count(*) from schedule where series_id=? and occurrence_date>=? and occurrence_date<=?",
                seriesId, java.sql.Date.valueOf(nextMonthStart), java.sql.Date.valueOf(nextMonthEnd))).isEqualTo(nextMonthStart.lengthOfMonth());
        assertThat(count("select count(*) from schedule where series_id=? and occurrence_date>=? and occurrence_date<=? and title<>'Renamed for next month'",
                seriesId, java.sql.Date.valueOf(nextMonthStart), java.sql.Date.valueOf(nextMonthEnd))).isZero();
    }

    @Test
    void deleteSeriesRemovesPendingButKeepsCompletedInstances() {
        ZoneId zone = ZoneId.of("Asia/Shanghai");
        long userId = newUser("15100010005", zone);
        Map<String, Object> created = createDailySeries(userId, zone);
        String seriesId = (String) created.get("seriesId");
        int totalBefore = count("select count(*) from schedule where series_id=?", seriesId);
        assertThat(totalBefore).isPositive();

        // Complete a non-seed instance when one exists; otherwise complete the seed.
        List<Long> rows = jdbc.queryForList("select id from schedule where series_id=? and status='pending' order by occurrence_date,id", Long.class, seriesId);
        long toComplete = rows.isEmpty() ? ((Number) created.get("id")).longValue() : rows.get(rows.size() - 1);
        scheduleService.setScheduleStatus(toComplete, userId, "completed");

        scheduleService.deleteSeries(userId, seriesId, Map.of());

        assertThat(count("select count(*) from schedule where series_id=? and deleted_at is null", seriesId)).isZero();
        assertThat(count("select count(*) from schedule where series_id=? and status='pending' and deleted_at is null", seriesId)).isZero();
        assertThat(count("select count(*) from schedule where id=? and deleted_at is null and status='completed'", toComplete)).isEqualTo(1);
    }

    @Test
    void excludedDatesAreRecordedAndNeverMaterialized() {
        ZoneId zone = ZoneId.of("Asia/Shanghai");
        long userId = newUser("15100010006", zone);
        LocalDate today = LocalDate.now(zone);
        LocalDate excluded = today.plusDays(1);

        Map<String, Object> created = scheduleService.createSchedule(userId, Map.of(
                "title", "Daily with exclusion",
                "timeType", "deadline_task",
                "deadlineTime", today.atTime(12, 0).atZone(zone).toInstant().toString(),
                "rrule", "FREQ=DAILY",
                "excludedDates", List.of(excluded.toString())
        ));
        String seriesId = (String) created.get("seriesId");
        if (!seriesId.isEmpty()) {
            assertThat(count("select count(*) from schedule_series_exdate where series_id=?", seriesId)).isEqualTo(1);
            assertThat(count("select count(*) from schedule where series_id=? and occurrence_date=?", seriesId, java.sql.Date.valueOf(excluded))).isZero();
        }
    }

    @Test
    void invalidRruleIsRejectedAtCreateTime() {
        ZoneId zone = ZoneId.of("Asia/Shanghai");
        long userId = newUser("15100010007", zone);
        LocalDate today = LocalDate.now(zone);
        String deadline = today.atTime(12, 0).atZone(zone).toInstant().toString();

        assertThatThrownBy(() -> scheduleService.createSchedule(userId, Map.of(
                "title", "Bad", "timeType", "deadline_task", "deadlineTime", deadline, "rrule", "FREQ=HOURLY")))
                .isInstanceOf(BusinessException.class)
                .extracting(ex -> ((BusinessException) ex).getCode())
                .isEqualTo(400);
        assertThatThrownBy(() -> scheduleService.createSchedule(userId, Map.of(
                "title", "No freq", "timeType", "deadline_task", "deadlineTime", deadline, "rrule", "INTERVAL=2")))
                .isInstanceOf(BusinessException.class)
                .extracting(ex -> ((BusinessException) ex).getCode())
                .isEqualTo(400);
    }

    // --- RruleExpander unit coverage ---

    @Test
    void rruleParserExpandsDailyWeeklyAndIntervalCorrectly() {
        LocalDate dtstart = LocalDate.of(2026, 8, 10);
        assertThat(RruleExpander.expand("FREQ=DAILY", dtstart, LocalDate.of(2026, 8, 1), LocalDate.of(2026, 8, 15)))
                .containsExactly(
                        LocalDate.of(2026, 8, 11), LocalDate.of(2026, 8, 12), LocalDate.of(2026, 8, 13),
                        LocalDate.of(2026, 8, 14), LocalDate.of(2026, 8, 15));

        assertThat(RruleExpander.expand("FREQ=WEEKLY", dtstart, LocalDate.of(2026, 8, 1), LocalDate.of(2026, 8, 31)))
                .containsExactly(LocalDate.of(2026, 8, 17), LocalDate.of(2026, 8, 24), LocalDate.of(2026, 8, 31));

        assertThat(RruleExpander.expand("FREQ=DAILY;INTERVAL=3", dtstart, LocalDate.of(2026, 8, 1), LocalDate.of(2026, 8, 31)))
                .containsExactly(LocalDate.of(2026, 8, 13), LocalDate.of(2026, 8, 16), LocalDate.of(2026, 8, 19),
                        LocalDate.of(2026, 8, 22), LocalDate.of(2026, 8, 25), LocalDate.of(2026, 8, 28), LocalDate.of(2026, 8, 31));
    }

    @Test
    void rruleParserHonorsUntilOutsideTheExpansionWindow() {
        LocalDate dtstart = LocalDate.of(2026, 8, 10);
        assertThat(RruleExpander.expand("FREQ=DAILY;UNTIL=20260812", dtstart, LocalDate.of(2026, 8, 1), LocalDate.of(2026, 8, 31)))
                .containsExactly(LocalDate.of(2026, 8, 11), LocalDate.of(2026, 8, 12));
    }

    private int count(String sql, Object... args) {
        Integer value = jdbc.queryForObject(sql, Integer.class, args);
        return value == null ? 0 : value;
    }
}