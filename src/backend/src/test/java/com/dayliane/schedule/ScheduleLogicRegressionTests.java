package com.dayliane.schedule;

import com.dayliane.auth.AuthService;
import com.dayliane.common.BusinessException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.sql.Timestamp;
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
        for (String table : List.of("fatigue_alert_log", "fatigue_survey_prompt_log", "fatigue_survey_skip", "fatigue_survey", "fatigue_daily_summary", "user_fatigue_profile", "notification", "auth_revoked_access_token", "auth_refresh_token", "reminder", "schedule", "task_group", "user")) {
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

    @Test
    @SuppressWarnings("unchecked")
    void quickScopeOnlyReturnsOverdueTodayAndUnscheduledItems() {
        ZoneId zone = ZoneId.of("Asia/Shanghai");
        long userId = authService.register("15100000003", "Abc12345", "Quick Scope User", zone.getId());
        String today = LocalDate.now(zone).atTime(12, 0).atZone(zone).toInstant().toString();
        String future = LocalDate.now(zone).plusDays(2).atTime(12, 0).atZone(zone).toInstant().toString();

        scheduleService.createSchedule(userId, Map.of("title", "Overdue", "timeType", "deadline_task", "deadlineTime", Instant.now().minusSeconds(3600).toString()));
        scheduleService.createSchedule(userId, Map.of("title", "Today", "timeType", "deadline_task", "deadlineTime", today));
        scheduleService.createSchedule(userId, Map.of("title", "Future", "timeType", "deadline_task", "deadlineTime", future));

        Long groupId = jdbc.queryForObject("select id from task_group where user_id=? and scope='personal' order by id limit 1", Long.class, userId);
        String groupName = jdbc.queryForObject("select name from task_group where id=?", String.class, groupId);
        jdbc.update("insert into schedule (user_id,title,description,group_id,group_name,sort_order,time_type,status,urgency_level,fatigue_level) values (?,?,?,?,?,40,'deadline_task','pending',3,3)",
                userId, "Unscheduled", "", groupId, groupName);

        Map<String, Object> result = scheduleService.listSchedules(userId, 1, 32, "pending", null, null, null, null,
                "time_asc", "time", null, null, true);
        List<Map<String, Object>> rows = (List<Map<String, Object>>) result.get("list");
        List<String> titles = rows.stream().map(item -> String.valueOf(item.get("title"))).toList();

        assertThat(result).containsEntry("scope", "quick").containsEntry("total", 3);
        assertThat(titles).containsExactlyInAnyOrder("Overdue", "Today", "Unscheduled").doesNotContain("Future");
        assertThat((List<Map<String, Object>>) result.get("sectionSummaries"))
                .extracting(item -> item.get("key"))
                .contains("time:overdue", "time:unscheduled");
    }

    @Test
    @SuppressWarnings("unchecked")
    void listReturnsCompleteServerPartitionsAndMonotonicRevision() {
        ZoneId zone = ZoneId.of("Asia/Shanghai");
        long userId = authService.register("15100000004", "Abc12345", "Revision User", zone.getId());
        LocalDate date = LocalDate.now(zone).plusDays(1);
        long pendingId = ((Number) scheduleService.createSchedule(userId, Map.of(
                "title", "Pending schedule",
                "timeType", "deadline_task",
                "deadlineTime", date.atTime(10, 0).atZone(zone).toInstant().toString(),
                "urgencyLevel", 5,
                "fatigueLevel", 4
        )).get("id")).longValue();
        long completedId = ((Number) scheduleService.createSchedule(userId, Map.of(
                "title", "Completed schedule",
                "timeType", "deadline_task",
                "deadlineTime", date.atTime(11, 0).atZone(zone).toInstant().toString(),
                "fatigueLevel", 2
        )).get("id")).longValue();
        scheduleService.setScheduleStatus(completedId, userId, "completed");

        Map<String, Object> first = scheduleService.listSchedules(userId, 1, 1, null, null, null, null, null,
                "manual", "group", null, null, false);
        List<Map<String, Object>> summaries = (List<Map<String, Object>>) first.get("sectionSummaries");
        long firstRevision = ((Number) first.get("dataRevision")).longValue();

        assertThat(first).containsEntry("total", 2).containsEntry("revision", String.valueOf(firstRevision));
        assertThat((List<?>) first.get("list")).hasSize(1);
        assertThat(summaries).extracting(item -> item.get("key")).contains("group:completed");
        assertThat(summaries).extracting(item -> item.get("total")).containsExactlyInAnyOrder(1, 1);

        scheduleService.updateSchedule(pendingId, userId, Map.of("title", "Renamed schedule"));
        Map<String, Object> second = scheduleService.listSchedules(userId, 1, 1, null, null, null, null, null,
                "manual", "group", null, null, false);
        long secondRevision = ((Number) second.get("dataRevision")).longValue();

        assertThat(secondRevision).isGreaterThan(firstRevision);
        assertThat(second).containsEntry("revision", String.valueOf(secondRevision));
    }

    @Test
    @SuppressWarnings("unchecked")
    void timeAscendingUsesTimeSectionsAndUrgencyAsTheStableTieBreaker() {
        long userId = authService.register("15100000005", "Abc12345", "Time Sort User", "Asia/Shanghai");
        String sharedTime = Instant.now().plusSeconds(7200).toString();
        scheduleService.createSchedule(userId, Map.of("title", "Low urgency", "timeType", "deadline_task", "deadlineTime", sharedTime, "urgencyLevel", 2));
        scheduleService.createSchedule(userId, Map.of("title", "High urgency", "timeType", "deadline_task", "deadlineTime", sharedTime, "urgencyLevel", 5));
        scheduleService.createSchedule(userId, Map.of("title", "Overdue", "timeType", "deadline_task", "deadlineTime", Instant.now().minusSeconds(3600).toString(), "urgencyLevel", 1));

        Map<String, Object> result = scheduleService.listSchedules(userId, 1, 20, "pending", null, null, null, null,
                "time_asc", "time", null, null, false);
        List<Map<String, Object>> rows = (List<Map<String, Object>>) result.get("list");

        assertThat(rows).extracting(item -> item.get("title")).containsExactly("Overdue", "High urgency", "Low urgency");
        assertThat(rows).extracting(item -> item.get("sectionKey")).first().isEqualTo("time:overdue");
    }

    @Test
    @SuppressWarnings("unchecked")
    void completedSchedulesDefaultToCompletedTimeDescendingWithIdAsTheTieBreaker() {
        long userId = authService.register("15100000007", "Abc12345", "Completed Sort User", "Asia/Shanghai");
        long oldestId = ((Number) scheduleService.createSchedule(userId, Map.of(
                "title", "Oldest completion", "timeType", "deadline_task", "deadlineTime", Instant.now().plusSeconds(3600).toString()
        )).get("id")).longValue();
        long firstTiedId = ((Number) scheduleService.createSchedule(userId, Map.of(
                "title", "First tied completion", "timeType", "deadline_task", "deadlineTime", Instant.now().plusSeconds(7200).toString()
        )).get("id")).longValue();
        long secondTiedId = ((Number) scheduleService.createSchedule(userId, Map.of(
                "title", "Second tied completion", "timeType", "deadline_task", "deadlineTime", Instant.now().plusSeconds(10800).toString()
        )).get("id")).longValue();
        scheduleService.setScheduleStatus(oldestId, userId, "completed");
        scheduleService.setScheduleStatus(firstTiedId, userId, "completed");
        scheduleService.setScheduleStatus(secondTiedId, userId, "completed");
        jdbc.update("update schedule set completed_at=? where id=?", Timestamp.from(Instant.parse("2099-08-19T08:00:00Z")), oldestId);
        Timestamp tiedCompletion = Timestamp.from(Instant.parse("2099-08-20T08:00:00Z"));
        jdbc.update("update schedule set completed_at=? where id in (?,?)", tiedCompletion, firstTiedId, secondTiedId);

        Map<String, Object> result = scheduleService.listSchedules(userId, 1, 20, "completed", null, null, null, null,
                "manual", "time", null, null, false);
        List<Map<String, Object>> rows = (List<Map<String, Object>>) result.get("list");

        assertThat(rows).extracting(item -> item.get("id")).containsExactly(secondTiedId, firstTiedId, oldestId);
    }

    @Test
    @SuppressWarnings("unchecked")
    void levelSummariesAlwaysContainFiveLevelsAndSeparatePlannedFromCompletedLoad() {
        long userId = authService.register("15100000008", "Abc12345", "Level Summary User", "Asia/Shanghai");
        scheduleService.createSchedule(userId, Map.of(
                "title", "Pending high urgency", "timeType", "deadline_task",
                "deadlineTime", Instant.now().plusSeconds(3600).toString(), "urgencyLevel", 5, "fatigueLevel", 4
        ));
        long completedId = ((Number) scheduleService.createSchedule(userId, Map.of(
                "title", "Completed high urgency", "timeType", "deadline_task",
                "deadlineTime", Instant.now().plusSeconds(7200).toString(), "urgencyLevel", 5, "fatigueLevel", 2
        )).get("id")).longValue();
        scheduleService.setScheduleStatus(completedId, userId, "completed");

        Map<String, Object> urgencyResult = scheduleService.listSchedules(userId, 1, 1, null, null, null, null, null,
                "manual", "urgency", null, null, false);
        List<Map<String, Object>> urgencySummaries = (List<Map<String, Object>>) urgencyResult.get("sectionSummaries");
        assertThat((List<?>) urgencyResult.get("list")).hasSize(1);
        assertThat(urgencySummaries).extracting(item -> item.get("key"))
                .containsExactly("urgency:5", "urgency:4", "urgency:3", "urgency:2", "urgency:1");
        assertThat(urgencySummaries).extracting(item -> item.get("total"))
                .containsExactly(2, 0, 0, 0, 0);
        assertThat(urgencySummaries.get(0))
                .containsEntry("pendingCount", 1L)
                .containsEntry("completedCount", 1L)
                .containsEntry("plannedLoad", new java.math.BigDecimal("5.000"))
                .containsEntry("completedLoad", new java.math.BigDecimal("2.000"));

        Map<String, Object> fatigueResult = scheduleService.listSchedules(userId, 1, 1, null, null, null, null, null,
                "manual", "fatigue", null, null, false);
        List<Map<String, Object>> fatigueSummaries = (List<Map<String, Object>>) fatigueResult.get("sectionSummaries");
        assertThat(fatigueSummaries).extracting(item -> item.get("key"))
                .containsExactly("fatigue:5", "fatigue:4", "fatigue:3", "fatigue:2", "fatigue:1");
        assertThat(fatigueSummaries).extracting(item -> item.get("total"))
                .containsExactly(0, 1, 0, 1, 0);
        assertThat(fatigueSummaries.get(1))
                .containsEntry("plannedLoad", new java.math.BigDecimal("5.000"))
                .containsEntry("completedLoad", java.math.BigDecimal.ZERO);
        assertThat(fatigueSummaries.get(3))
                .containsEntry("plannedLoad", java.math.BigDecimal.ZERO)
                .containsEntry("completedLoad", new java.math.BigDecimal("2.000"));
    }

    @Test
    void oversizedLevelsAndDatabaseConstraintViolationsAreRejected() {
        long userId = authService.register("15100000006", "Abc12345", "Level Guard User", "Asia/Shanghai");

        assertThatThrownBy(() -> scheduleService.createSchedule(userId, Map.of(
                "title", "Overflow",
                "timeType", "deadline_task",
                "deadlineTime", Instant.now().plusSeconds(3600).toString(),
                "urgencyLevel", Long.MAX_VALUE)))
                .isInstanceOf(BusinessException.class)
                .extracting(ex -> ((BusinessException) ex).getCode())
                .isEqualTo(400);

        long scheduleId = ((Number) scheduleService.createSchedule(userId, Map.of(
                "title", "Valid",
                "timeType", "deadline_task",
                "deadlineTime", Instant.now().plusSeconds(3600).toString()
        )).get("id")).longValue();
        assertThatThrownBy(() -> scheduleService.updateSchedule(scheduleId, userId, Map.of("fatigueLevel", Long.MAX_VALUE)))
                .isInstanceOf(BusinessException.class)
                .extracting(ex -> ((BusinessException) ex).getCode())
                .isEqualTo(400);
        assertThatThrownBy(() -> jdbc.update("update schedule set urgency_level=6 where id=?", scheduleId))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> jdbc.update("update schedule set completed_fatigue_weight=0 where id=?", scheduleId))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    private int count(String sql, Object... args) {
        Integer value = jdbc.queryForObject(sql, Integer.class, args);
        return value == null ? 0 : value;
    }
}
