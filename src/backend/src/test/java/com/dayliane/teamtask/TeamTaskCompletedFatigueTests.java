package com.dayliane.teamtask;

import com.dayliane.admin.AdminService;
import com.dayliane.auth.AuthService;
import com.dayliane.common.BusinessException;
import com.dayliane.fatigue.FatigueService;
import com.dayliane.team.TeamService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
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
class TeamTaskCompletedFatigueTests {

    private static final ZoneId USER_ZONE = ZoneId.of("Asia/Shanghai");

    @Autowired AuthService authService;
    @Autowired FatigueService fatigueService;
    @Autowired TeamService teamService;
    @Autowired TeamTaskService teamTaskService;
    @Autowired AdminService adminService;
    @Autowired JdbcTemplate jdbc;

    @BeforeEach
    void cleanDatabase() {
        for (String table : List.of(
                "fatigue_alert_log", "fatigue_survey_prompt_log", "fatigue_survey_skip", "fatigue_survey", "fatigue_daily_summary", "user_fatigue_profile",
                "notification", "notification_preference", "reminder_preset", "reminder",
                "team_task_event", "team_task_reminder_plan", "team_task_assignee", "team_task",
                "schedule", "task_group", "team_member", "team",
                "admin_operation_log", "admin_user",
                "auth_revoked_access_token", "auth_refresh_token", "auth_email_otp", "user")) {
            jdbc.update("delete from " + ("user".equals(table) ? "`user`" : table));
        }
    }

    @Test
    void completeRequiresFatigueLevelWhenTrackingEnabled() {
        long owner = register("15110000001", "Owner");
        long member = register("15110000002", "Member");
        long teamId = id(teamService.createTeam(owner, "Fatigue Team"));
        teamService.joinTeam(member, text(teamService.teamDetail(teamId, owner), "inviteCode"));
        long taskId = acceptedTask(teamId, owner, member, "Requires level");

        assertBusinessCode(400, () -> teamTaskService.completeTeamTask(taskId, member, Map.of()));
        assertBusinessCode(400, () -> teamTaskService.completeTeamTask(taskId, member, Map.of("fatigueLevel", 6)));
        assertBusinessCode(400, () -> teamTaskService.completeTeamTask(taskId, member, Map.of("fatigueLevel", "x")));
    }

    @Test
    void completeAllowedWithoutFatigueLevelWhenTrackingDisabledSkipsSnapshot() {
        long owner = register("15110000011", "Owner");
        long member = register("15110000012", "Member");
        long teamId = id(teamService.createTeam(owner, "Fatigue Team"));
        teamService.joinTeam(member, text(teamService.teamDetail(teamId, owner), "inviteCode"));
        long taskId = acceptedTask(teamId, owner, member, "No level needed");
        fatigueService.updatePreferences(member, Map.of("fatigueTrackingEnabled", false));

        Map<String, Object> detail = teamTaskService.completeTeamTask(taskId, member, Map.of());

        assertThat(detail).containsEntry("status", "completed");
        assertThat(detail.get("completedFatigueLevel")).isNull();
        assertThat(detail.get("completedFatigueWeight")).isNull();
        assertThat(decimal(fatigueService.daily(member, LocalDate.now(USER_ZONE)), "teamCompletedLoad")).isEqualByComparingTo("0");
        assertThat(decimal(jdbc.queryForObject("select completed_fatigue_weight from team_task_assignee where task_id=? and user_id=?",
                BigDecimal.class, taskId, member))).isNull();
        assertThat(detail.get("completedAt")).isNotNull();
    }

    @Test
    void completionWritesPersonalizedWeightSnapshot() {
        long owner = register("15110000021", "Owner");
        long member = register("15110000022", "Member");
        long teamId = id(teamService.createTeam(owner, "Fatigue Team"));
        teamService.joinTeam(member, text(teamService.teamDetail(teamId, owner), "inviteCode"));
        long taskId = acceptedTask(teamId, owner, member, "Snapshot weight");
        fatigueService.profile(member);
        jdbc.update("update user_fatigue_profile set level_5_weight=9.5 where user_id=?", member);

        Map<String, Object> detail = teamTaskService.completeTeamTask(taskId, member, Map.of("fatigueLevel", 5));

        assertThat(detail).containsEntry("completedFatigueLevel", 5);
        assertThat(decimal(detail.get("completedFatigueWeight"))).isEqualByComparingTo("9.5");
        assertThat(decimal(jdbc.queryForObject("select completed_fatigue_weight from team_task_assignee where task_id=? and user_id=?",
                BigDecimal.class, taskId, member))).isEqualByComparingTo("9.5");
        assertThat(decimal(fatigueService.daily(member, LocalDate.now(USER_ZONE)), "teamCompletedLoad")).isEqualByComparingTo("9.5");
    }

    @Test
    void duplicateCompleteIsIdempotentAndCountsOnce() {
        long owner = register("15110000031", "Owner");
        long member = register("15110000032", "Member");
        long teamId = id(teamService.createTeam(owner, "Fatigue Team"));
        teamService.joinTeam(member, text(teamService.teamDetail(teamId, owner), "inviteCode"));
        long taskId = acceptedTask(teamId, owner, member, "Duplicate complete");

        teamTaskService.completeTeamTask(taskId, member, Map.of("fatigueLevel", 4));
        teamTaskService.completeTeamTask(taskId, member, Map.of("fatigueLevel", 1));

        assertThat(count("select count(*) from team_task_assignee where task_id=? and user_id=? and completed_fatigue_level is not null", taskId, member)).isEqualTo(1);
        assertThat(jdbc.queryForObject("select completed_fatigue_level from team_task_assignee where task_id=? and user_id=?",
                Integer.class, taskId, member)).isEqualTo(4);
        BigDecimal expected = fatigueService.currentWeight(member, 4);
        assertThat(decimal(fatigueService.daily(member, LocalDate.now(USER_ZONE)), "teamCompletedLoad")).isEqualByComparingTo(expected.toString());
    }

    @Test
    void concurrentCompletionWritesSingleSnapshot() throws Exception {
        long owner = register("15110000041", "Owner");
        long member = register("15110000042", "Member");
        long teamId = id(teamService.createTeam(owner, "Fatigue Team"));
        teamService.joinTeam(member, text(teamService.teamDetail(teamId, owner), "inviteCode"));
        long taskId = acceptedTask(teamId, owner, member, "Concurrent complete");

        List<Map<String, Object>> results = runConcurrently(() -> teamTaskService.completeTeamTask(taskId, member, Map.of("fatigueLevel", 3)));

        assertThat(results).hasSize(2).allSatisfy(r -> assertThat(r).containsEntry("completedFatigueLevel", 3));
        assertThat(count("select count(*) from team_task_assignee where task_id=? and user_id=? and completed_fatigue_level is not null", taskId, member)).isEqualTo(1);
        BigDecimal expected = fatigueService.currentWeight(member, 3);
        assertThat(decimal(fatigueService.daily(member, LocalDate.now(USER_ZONE)), "teamCompletedLoad")).isEqualByComparingTo(expected.toString());
    }

    @Test
    void multiAssigneeRecordsIndependentlyWithoutCrossUserLeak() {
        long owner = register("15110000051", "Owner");
        long memberA = register("15110000052", "Member A");
        long memberB = register("15110000053", "Member B");
        long teamId = id(teamService.createTeam(owner, "Fatigue Team"));
        teamService.joinTeam(memberA, text(teamService.teamDetail(teamId, owner), "inviteCode"));
        teamService.joinTeam(memberB, text(teamService.teamDetail(teamId, owner), "inviteCode"));
        long taskId = id(teamTaskService.createTeamTask(owner, Map.of("teamId", teamId, "title", "Multi assignee",
                "assigneeUserIds", List.of(memberA, memberB))));
        teamTaskService.teamTaskAssigneeTransition(taskId, memberA, "accepted", List.of("pending"));
        teamTaskService.teamTaskAssigneeTransition(taskId, memberB, "accepted", List.of("pending"));

        teamTaskService.completeTeamTask(taskId, memberA, Map.of("fatigueLevel", 1));
        teamTaskService.completeTeamTask(taskId, memberB, Map.of("fatigueLevel", 5));

        assertThat(decimal(jdbc.queryForObject("select completed_fatigue_weight from team_task_assignee where task_id=? and user_id=?",
                BigDecimal.class, taskId, memberA))).isEqualByComparingTo(fatigueService.currentWeight(memberA, 1).toString());
        assertThat(decimal(jdbc.queryForObject("select completed_fatigue_weight from team_task_assignee where task_id=? and user_id=?",
                BigDecimal.class, taskId, memberB))).isEqualByComparingTo(fatigueService.currentWeight(memberB, 5).toString());
        assertThat(decimal(fatigueService.daily(memberA, LocalDate.now(USER_ZONE)), "teamCompletedLoad"))
                .isEqualByComparingTo(fatigueService.currentWeight(memberA, 1).toString());
        assertThat(decimal(fatigueService.daily(memberB, LocalDate.now(USER_ZONE)), "teamCompletedLoad"))
                .isEqualByComparingTo(fatigueService.currentWeight(memberB, 5).toString());

        Map<String, Object> detailA = teamTaskService.teamTaskDetail(taskId, memberA);
        assertThat(detailA).containsEntry("completedFatigueLevel", 1);
        assertThat(detailA.get("completedAt")).isNotNull();
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> assigneeRowsForA = (List<Map<String, Object>>) detailA.get("assignees");
        Map<String, Object> rowA = assigneeRowsForA.stream()
                .filter(row -> ((Number) row.get("userId")).longValue() == memberA)
                .findFirst().orElseThrow();
        Map<String, Object> rowB = assigneeRowsForA.stream()
                .filter(row -> ((Number) row.get("userId")).longValue() == memberB)
                .findFirst().orElseThrow();
        assertThat(rowA).containsKeys("completedAt", "completedFatigueLevel", "completedFatigueWeight");
        assertThat(rowB).doesNotContainKeys("completedAt", "completedFatigueLevel", "completedFatigueWeight");

        Map<String, Object> detailB = teamTaskService.teamTaskDetail(taskId, memberB);
        assertThat(detailB).containsEntry("completedFatigueLevel", 5);
        assertThat(detailB.get("completedAt")).isNotNull();
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> assigneeRowsForOwner = (List<Map<String, Object>>) teamTaskService.teamTaskDetail(taskId, owner).get("assignees");
        assertThat(assigneeRowsForOwner).allSatisfy(row ->
                assertThat(row).doesNotContainKeys("completedAt", "completedFatigueLevel", "completedFatigueWeight"));
    }

    @Test
    void deletingSurveyHistoryClearsOnlyMyTeamFatigueSnapshot() {
        long owner = register("15110000111", "Owner");
        long member = register("15110000112", "Member");
        long teamId = id(teamService.createTeam(owner, "Delete history team"));
        teamService.joinTeam(member, text(teamService.teamDetail(teamId, owner), "inviteCode"));
        long taskId = acceptedTask(teamId, owner, member, "Retain completion date");
        teamTaskService.completeTeamTask(taskId, member, Map.of("fatigueLevel", 4));

        Map<String, Object> result = fatigueService.deleteSurveyHistory(member);

        assertThat(result).containsEntry("clearedTeamSnapshotCount", 1);
        assertThat(jdbc.queryForObject("select completed_at from team_task_assignee where task_id=? and user_id=?", Timestamp.class, taskId, member)).isNotNull();
        assertThat(jdbc.queryForObject("select completed_fatigue_level from team_task_assignee where task_id=? and user_id=?", Integer.class, taskId, member)).isNull();
        assertThat(jdbc.queryForObject("select completed_fatigue_weight from team_task_assignee where task_id=? and user_id=?", BigDecimal.class, taskId, member)).isNull();
    }

    @Test
    void retentionClearsOldTeamFatigueSnapshotButKeepsCompletionDate() {
        long owner = register("15110000121", "Owner");
        long member = register("15110000122", "Member");
        long teamId = id(teamService.createTeam(owner, "Retention team"));
        teamService.joinTeam(member, text(teamService.teamDetail(teamId, owner), "inviteCode"));
        long taskId = acceptedTask(teamId, owner, member, "Old completion");
        teamTaskService.completeTeamTask(taskId, member, Map.of("fatigueLevel", 3));
        Timestamp oldCompletion = Timestamp.from(Instant.now().minus(Duration.ofDays(181)));
        jdbc.update("update team_task_assignee set completed_at=? where task_id=? and user_id=?", oldCompletion, taskId, member);

        fatigueService.cleanupRetention();

        Timestamp retainedCompletion = jdbc.queryForObject("select completed_at from team_task_assignee where task_id=? and user_id=?", Timestamp.class, taskId, member);
        assertThat(retainedCompletion).isNotNull();
        assertThat(retainedCompletion.getTime()).isEqualTo(oldCompletion.getTime());
        assertThat(jdbc.queryForObject("select completed_fatigue_level from team_task_assignee where task_id=? and user_id=?", Integer.class, taskId, member)).isNull();
        assertThat(jdbc.queryForObject("select completed_fatigue_weight from team_task_assignee where task_id=? and user_id=?", BigDecimal.class, taskId, member)).isNull();
    }

    @Test
    void rejectingAcceptedAssignmentClearsAcceptedTimestamp() {
        long owner = register("15110000131", "Owner");
        long member = register("15110000132", "Member");
        long teamId = id(teamService.createTeam(owner, "Reject team"));
        teamService.joinTeam(member, text(teamService.teamDetail(teamId, owner), "inviteCode"));
        long taskId = acceptedTask(teamId, owner, member, "Reject after accept");

        assertThat(jdbc.queryForObject("select accepted_at from team_task_assignee where task_id=? and user_id=?", Timestamp.class, taskId, member)).isNotNull();
        teamTaskService.teamTaskAssigneeTransition(taskId, member, "rejected", List.of("accepted"));

        assertThat(jdbc.queryForObject("select accepted_at from team_task_assignee where task_id=? and user_id=?", Timestamp.class, taskId, member)).isNull();
        assertThat(jdbc.queryForObject("select rejected_at from team_task_assignee where task_id=? and user_id=?", Timestamp.class, taskId, member)).isNotNull();
    }

    @Test
    void managerCannotSetCompletedAndCorrectionClearsSnapshot() {
        long owner = register("15110000061", "Owner");
        long memberA = register("15110000062", "Member A");
        long memberB = register("15110000063", "Member B");
        long teamId = id(teamService.createTeam(owner, "Fatigue Team"));
        teamService.joinTeam(memberA, text(teamService.teamDetail(teamId, owner), "inviteCode"));
        teamService.joinTeam(memberB, text(teamService.teamDetail(teamId, owner), "inviteCode"));
        long taskId = id(teamTaskService.createTeamTask(owner, Map.of("teamId", teamId, "title", "Two assignee",
                "assigneeUserIds", List.of(memberA, memberB))));
        teamTaskService.teamTaskAssigneeTransition(taskId, memberA, "accepted", List.of("pending"));
        teamTaskService.teamTaskAssigneeTransition(taskId, memberB, "accepted", List.of("pending"));

        teamTaskService.completeTeamTask(taskId, memberA, Map.of("fatigueLevel", 4));
        long assigneeAId = assigneeId(taskId, memberA);

        assertBusinessCode(400, () -> teamTaskService.correctTeamTaskAssigneeStatus(taskId, assigneeAId, owner, "completed"));
        assertThat(count("select count(*) from team_task_assignee where id=? and completed_fatigue_level is not null", assigneeAId)).isEqualTo(1);

        teamTaskService.correctTeamTaskAssigneeStatus(taskId, assigneeAId, owner, "pending");

        assertThat(jdbc.queryForObject("select status from team_task_assignee where id=?", String.class, assigneeAId)).isEqualTo("pending");
        assertThat(jdbc.queryForObject("select completed_at from team_task_assignee where id=?", java.sql.Timestamp.class, assigneeAId)).isNull();
        assertThat(jdbc.queryForObject("select completed_fatigue_level from team_task_assignee where id=?", Integer.class, assigneeAId)).isNull();
        assertThat(decimal(fatigueService.daily(memberA, LocalDate.now(USER_ZONE)), "teamCompletedLoad")).isEqualByComparingTo("0");
    }

    @Test
    void overallCompletedTaskCannotBeReopened() {
        long owner = register("15110000071", "Owner");
        long member = register("15110000072", "Member");
        long teamId = id(teamService.createTeam(owner, "Fatigue Team"));
        teamService.joinTeam(member, text(teamService.teamDetail(teamId, owner), "inviteCode"));
        long taskId = acceptedTask(teamId, owner, member, "Single assignee");
        teamTaskService.completeTeamTask(taskId, member, Map.of("fatigueLevel", 2));
        long assigneeId = assigneeId(taskId, member);

        assertThat(teamTaskService.teamTaskDetail(taskId, owner)).containsEntry("status", "completed");
        assertBusinessCode(400, () -> teamTaskService.correctTeamTaskAssigneeStatus(taskId, assigneeId, owner, "pending"));
    }

    @Test
    void adminCannotSetCompleted() {
        long owner = register("15110000081", "Owner");
        long member = register("15110000082", "Member");
        long teamId = id(teamService.createTeam(owner, "Fatigue Team"));
        teamService.joinTeam(member, text(teamService.teamDetail(teamId, owner), "inviteCode"));
        long taskId = id(teamTaskService.createTeamTask(owner, Map.of("teamId", teamId, "title", "Admin target",
                "assigneeUserIds", List.of(member))));
        teamTaskService.teamTaskAssigneeTransition(taskId, member, "accepted", List.of("pending"));
        long assigneeId = assigneeId(taskId, member);
        long adminId = createAdmin();

        assertBusinessCode(400, () -> adminService.adminCorrectAssigneeStatus(adminId, taskId, assigneeId, "completed", "127.0.0.1", "test"));
        assertThat(jdbc.queryForObject("select status from team_task_assignee where id=?", String.class, assigneeId)).isEqualTo("accepted");
    }

    @Test
    void crossTimezoneCompletionAttributedToUserLocalDate() {
        ZoneId zone = ZoneId.of("Pacific/Kiritimati");
        long owner = register("15110000091", "Owner", "Asia/Shanghai");
        long member = authService.register("15110000092", "Abc12345", "Member", zone.getId());
        long teamId = id(teamService.createTeam(owner, "TZ Team"));
        teamService.joinTeam(member, text(teamService.teamDetail(teamId, owner), "inviteCode"));
        long taskId = acceptedTask(teamId, owner, member, "Cross TZ");

        teamTaskService.completeTeamTask(taskId, member, Map.of("fatigueLevel", 3));

        LocalDate today = LocalDate.now(zone);
        assertThat(decimal(fatigueService.daily(member, today), "teamCompletedLoad"))
                .isEqualByComparingTo(fatigueService.currentWeight(member, 3).toString());
        assertThat(decimal(fatigueService.daily(member, today.minusDays(1)), "teamCompletedLoad")).isEqualByComparingTo("0");
    }

    @Test
    void historicalNoSnapshotIsNotBackfilled() {
        long owner = register("15110000093", "Owner");
        long member = register("15110000094", "Member");
        long teamId = id(teamService.createTeam(owner, "Legacy Team"));
        teamService.joinTeam(member, text(teamService.teamDetail(teamId, owner), "inviteCode"));
        long taskId = id(teamTaskService.createTeamTask(owner, Map.of("teamId", teamId, "title", "Legacy completed",
                "assigneeUserIds", List.of(member))));

        LocalDate date = LocalDate.now(USER_ZONE);
        jdbc.update("update team_task_assignee set status='completed', completed_at=?, completed_fatigue_level=null, completed_fatigue_weight=null where task_id=? and user_id=?",
                java.sql.Timestamp.from(Instant.now()), taskId, member);

        assertThat(decimal(fatigueService.daily(member, date), "teamCompletedLoad")).isEqualByComparingTo("0");
        assertThat(decimal(fatigueService.daily(member, date), "completedLoad")).isEqualByComparingTo("0");
    }

    @Test
    void teamCompletionDoesNotAffectPersonalPlannedOrCompletedLoad() {
        long owner = register("15110000095", "Owner");
        long member = register("15110000096", "Member");
        long teamId = id(teamService.createTeam(owner, "Load Team"));
        teamService.joinTeam(member, text(teamService.teamDetail(teamId, owner), "inviteCode"));
        long taskId = acceptedTask(teamId, owner, member, "Team load");
        teamTaskService.completeTeamTask(taskId, member, Map.of("fatigueLevel", 5));

        Map<String, Object> daily = fatigueService.daily(member, LocalDate.now(USER_ZONE));

        assertThat(decimal(daily, "plannedLoad")).isEqualByComparingTo("0");
        assertThat(decimal(daily, "completedLoad")).isEqualByComparingTo("0");
        assertThat(decimal(daily, "teamCompletedLoad")).isEqualByComparingTo(fatigueService.currentWeight(member, 5).toString());
        assertThat(decimal(daily, "totalCompletedLoad")).isEqualByComparingTo(fatigueService.currentWeight(member, 5).toString());
        assertThat(daily).doesNotContainKey("teamCompletedCount");
    }

    @Test
    void deactivatedCompletedAssigneeStillAppearsInDetailHistory() {
        long owner = register("15110000101", "Owner");
        long member = register("15110000102", "Member");
        long teamId = id(teamService.createTeam(owner, "History Team"));
        teamService.joinTeam(member, text(teamService.teamDetail(teamId, owner), "inviteCode"));
        long taskId = acceptedTask(teamId, owner, member, "Keep completed history");
        teamTaskService.completeTeamTask(taskId, member, Map.of("fatigueLevel", 3));

        // 后续重分配/移除会把已完成执行人置为非活跃，但其历史完成记录不应丢失
        jdbc.update("update team_task_assignee set is_active=false where task_id=? and user_id=?", taskId, member);

        List<Map<String, Object>> assignees = (List<Map<String, Object>>) teamTaskService.teamTaskDetail(taskId, owner).get("assignees");
        Map<String, Object> record = assignees.stream()
                .filter(a -> ((Number) a.get("userId")).longValue() == member)
                .findFirst().orElseThrow();
        assertThat(record.get("status")).isEqualTo("completed");
        assertThat(record.get("assignStatus")).isEqualTo("completed");
        assertThat(record.get("isCurrent")).isEqualTo(false);

        Map<String, Object> memberDetail = teamTaskService.teamTaskDetail(taskId, member);
        assertThat(memberDetail).containsEntry("assignStatus", "completed");
        assertThat(memberDetail.get("completedAt")).isNotNull();
        assertThat(memberDetail).containsEntry("completedFatigueLevel", 3);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> memberRows = (List<Map<String, Object>>) memberDetail.get("assignees");
        Map<String, Object> memberRecord = memberRows.stream()
                .filter(a -> ((Number) a.get("userId")).longValue() == member)
                .findFirst().orElseThrow();
        assertThat(memberRecord.get("completedAt")).isNotNull();
        assertThat(memberRecord).containsEntry("completedFatigueLevel", 3);

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> completedHistory = (List<Map<String, Object>>) teamTaskService
                .listMyTeamTasks(member, 1, 20, "completed", "", "", "", "manual")
                .get("list");
        assertThat(completedHistory).extracting(row -> ((Number) row.get("id")).longValue())
                .contains(taskId);
    }

    // ===== helpers =====

    private long acceptedTask(long teamId, long owner, long assignee, String title) {
        long taskId = id(teamTaskService.createTeamTask(owner, Map.of("teamId", teamId, "title", title, "assigneeUserIds", List.of(assignee))));
        teamTaskService.teamTaskAssigneeTransition(taskId, assignee, "accepted", List.of("pending"));
        return taskId;
    }

    private long assigneeId(long taskId, long userId) {
        return jdbc.queryForObject("select id from team_task_assignee where task_id=? and user_id=? and is_active=true", Long.class, taskId, userId);
    }

    private long createAdmin() {
        jdbc.update("insert into admin_user (username,password_hash,role,status) values (?,?,'super_admin','active')", "fatigue-admin", "x");
        return jdbc.queryForObject("select id from admin_user where username=?", Long.class, "fatigue-admin");
    }

    private long register(String phone, String nickname) {
        return register(phone, nickname, USER_ZONE.getId());
    }

    private long register(String phone, String nickname, String zoneId) {
        return authService.register(phone, "Abc12345", nickname, zoneId);
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

    private void assertBusinessCode(int code, ThrowingRunnable runnable) {
        assertThatThrownBy(runnable::run)
                .isInstanceOf(BusinessException.class)
                .extracting(ex -> ((BusinessException) ex).getCode())
                .isEqualTo(code);
    }

    private int count(String sql, Object... args) {
        Integer value = jdbc.queryForObject(sql, Integer.class, args);
        return value == null ? 0 : value;
    }

    private static long id(Map<String, Object> item) {
        return ((Number) item.get("id")).longValue();
    }

    private static String text(Map<String, Object> item, String key) {
        return String.valueOf(item.get(key));
    }

    private static BigDecimal decimal(Object value) {
        return value == null ? null : new BigDecimal(String.valueOf(value));
    }

    private static BigDecimal decimal(Map<String, Object> item, String key) {
        return decimal(item.get(key));
    }

    @FunctionalInterface
    private interface ThrowingRunnable {
        void run();
    }
}
