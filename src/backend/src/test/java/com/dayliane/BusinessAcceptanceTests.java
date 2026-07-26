package com.dayliane;

import com.dayliane.auth.AuthService;
import com.dayliane.common.BusinessException;
import com.dayliane.reminder.ReminderService;
import com.dayliane.schedule.ScheduleService;
import com.dayliane.team.TeamService;
import com.dayliane.teamtask.TeamTaskService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest
@ActiveProfiles("test")
class BusinessAcceptanceTests {

    @Autowired AuthService authService;
    @Autowired ScheduleService scheduleService;
    @Autowired TeamService teamService;
    @Autowired TeamTaskService teamTaskService;
    @Autowired ReminderService reminderService;
    @Autowired JdbcTemplate jdbc;

    @BeforeEach
    void cleanDatabase() {
        for (String table : List.of("notification", "reminder", "team_task_assignee", "team_task", "schedule", "task_group", "team_member", "team", "admin_operation_log", "admin_user", "user")) {
            jdbc.update("delete from " + ("user".equals(table) ? "`user`" : table));
        }
    }

    @Test
    void authRegisterLoginRefreshLogoutAndRateLimit() {
        long userId = register("15000000001", "User A");
        assertThat(userId).isPositive();

        assertBusinessCode(409, () -> authService.register("15000000001", "Abc12345", "Duplicate", "Asia/Shanghai"));
        assertBusinessCode(401, () -> authService.login("15000000001", "Wrong12345", "10.0.0.1"));

        Map<String, Object> login = authService.login("15000000001", "Abc12345", "10.0.0.1");
        String accessToken = text(login, "accessToken");
        String refreshToken = text(login, "refreshToken");
        assertThat(authService.requireUser("Bearer " + accessToken)).isEqualTo(userId);

        Map<String, Object> rotated = authService.refreshToken(refreshToken);
        assertThat(text(rotated, "refreshToken")).isNotEqualTo(refreshToken);
        assertBusinessCode(401, () -> authService.refreshToken(refreshToken));

        authService.logout("Bearer " + accessToken);
        assertBusinessCode(401, () -> authService.requireUser("Bearer " + accessToken));

        for (int i = 0; i < 5; i++) {
            assertBusinessCode(401, () -> authService.login("15000000001", "BadPass123", "10.0.0.2"));
        }
        assertBusinessCode(429, () -> authService.login("15000000001", "BadPass123", "10.0.0.2"));
    }

    @Test
    void scheduleStatusTransitionsAndReminderCancellation() {
        long userId = register("15000000002", "Schedule User");
        long scheduleId = id(scheduleService.createSchedule(userId, Map.of(
                "title", "Write report",
                "timeType", "deadline_task",
                "deadlineTime", future(2),
                "remindAt", future(1)
        )));
        assertThat(count("select count(*) from reminder where target_type='schedule' and target_id=? and status='pending'", scheduleId)).isEqualTo(1);

        assertThat(scheduleService.setScheduleStatus(scheduleId, userId, "completed")).containsEntry("status", "completed");
        assertThat(count("select count(*) from reminder where target_type='schedule' and target_id=? and status='cancelled'", scheduleId)).isEqualTo(1);
        assertBusinessCode(400, () -> scheduleService.setScheduleStatus(scheduleId, userId, "completed"));

        long cancelledId = id(scheduleService.createSchedule(userId, Map.of(
                "title", "Cancelled item",
                "timeType", "deadline_task",
                "deadlineTime", future(3)
        )));
        scheduleService.setScheduleStatus(cancelledId, userId, "cancelled");
        assertBusinessCode(400, () -> scheduleService.setScheduleStatus(cancelledId, userId, "completed"));
        assertThat(scheduleService.setScheduleStatus(cancelledId, userId, "pending")).containsEntry("status", "pending");
    }

    @Test
    void teamRolesPermissionsRemovalAndRejoinDoNotRestoreOldAssignments() {
        long owner = register("15000000003", "Owner");
        long admin = register("15000000004", "Admin");
        long member = register("15000000005", "Member");
        Map<String, Object> team = teamService.createTeam(owner, "Acceptance Team");
        long teamId = id(team);
        assertThat(team).containsEntry("role", "owner");

        teamService.joinTeam(admin, text(team, "inviteCode"));
        teamService.joinTeam(member, text(team, "inviteCode"));
        assertBusinessCode(403, () -> teamService.removeMember(teamId, admin, member));

        teamService.changeRole(teamId, admin, owner, "admin");
        Map<String, Object> task = teamTaskService.createTeamTask(owner, Map.of("teamId", teamId, "title", "Old assignment", "assigneeUserIds", List.of(member)));
        teamService.removeMember(teamId, member, admin);
        assertBusinessCode(403, () -> teamService.teamDetail(teamId, member));

        teamService.joinTeam(member, text(team, "inviteCode"));
        assertThat(teamTaskService.listMyTeamTasks(member, 1, 20, null)).containsEntry("total", 0);
        assertThat(count("select count(*) from team_task_assignee where task_id=? and user_id=? and is_active=false", id(task), member)).isEqualTo(1);
    }

    @Test
    void teamTaskStatusTransitionsReassignCancelAndRestore() {
        long owner = register("15000000006", "Owner");
        long b = register("15000000007", "B");
        long c = register("15000000008", "C");
        long d = register("15000000009", "D");
        long teamId = id(teamService.createTeam(owner, "Task Team"));
        String invite = text(teamService.teamDetail(teamId, owner), "inviteCode");
        teamService.joinTeam(b, invite);
        teamService.joinTeam(c, invite);
        teamService.joinTeam(d, invite);

        long allCompleted = id(teamTaskService.createTeamTask(owner, Map.of("teamId", teamId, "title", "All done", "assigneeUserIds", List.of(b))));
        teamTaskService.teamTaskAssigneeTransition(allCompleted, b, "accepted", List.of("pending"));
        assertThat(teamTaskService.teamTaskAssigneeTransition(allCompleted, b, "completed", List.of("accepted"))).containsEntry("status", "completed");

        long mixed = id(teamTaskService.createTeamTask(owner, Map.of("teamId", teamId, "title", "Mixed result", "assigneeUserIds", List.of(b, c))));
        teamTaskService.teamTaskAssigneeTransition(mixed, b, "accepted", List.of("pending"));
        teamTaskService.teamTaskAssigneeTransition(mixed, b, "completed", List.of("accepted"));
        assertThat(teamTaskService.teamTaskAssigneeTransition(mixed, c, "rejected", List.of("pending"))).containsEntry("status", "completed");

        long rejected = id(teamTaskService.createTeamTask(owner, Map.of("teamId", teamId, "title", "Rejected", "assigneeUserIds", List.of(b, c))));
        teamTaskService.teamTaskAssigneeTransition(rejected, b, "rejected", List.of("pending"));
        assertThat(teamTaskService.teamTaskAssigneeTransition(rejected, c, "rejected", List.of("pending"))).containsEntry("status", "all_rejected");
        assertThat(teamTaskService.reassignTeamTask(rejected, owner, b, d)).containsEntry("status", "active");
        assertThat(count("select count(*) from team_task_assignee where task_id=? and user_id=? and is_active=false", rejected, b)).isEqualTo(1);

        assertThat(teamTaskService.cancelTeamTask(rejected, owner)).containsEntry("status", "cancelled");
        assertThat(teamTaskService.restoreTeamTask(rejected, owner)).containsEntry("status", "active");
    }

    @Test
    void remindersCreateNotificationsCancelPendingAndDoNotSendTwice() {
        long owner = register("15000000010", "Owner");
        long b = register("15000000011", "B");
        long c = register("15000000012", "C");

        long scheduleId = id(scheduleService.createSchedule(owner, Map.of(
                "title", "Due now",
                "timeType", "deadline_task",
                "deadlineTime", future(1),
                "remindAt", past(1)
        )));
        assertThat(reminderService.scanReminders()).isEqualTo(1);
        assertThat(reminderService.scanReminders()).isZero();
        assertThat(count("select count(*) from notification where related_type='schedule' and related_id=? and reminder_id is not null", scheduleId)).isEqualTo(1);

        long teamId = id(teamService.createTeam(owner, "Reminder Team"));
        String invite = text(teamService.teamDetail(teamId, owner), "inviteCode");
        teamService.joinTeam(b, invite);
        teamService.joinTeam(c, invite);
        long taskId = id(teamTaskService.createTeamTask(owner, Map.of(
                "teamId", teamId,
                "title", "Future reminder task",
                "assigneeUserIds", List.of(b, c),
                "remindAt", future(1)
        )));
        assertThat(count("select count(*) from reminder where target_type='team_task' and target_id=? and status='pending'", taskId)).isEqualTo(2);
        teamTaskService.teamTaskAssigneeTransition(taskId, b, "rejected", List.of("pending"));
        assertThat(count("select count(*) from reminder where target_type='team_task' and target_id=? and user_id=? and status='cancelled'", taskId, b)).isEqualTo(1);
        assertThat(count("select count(*) from reminder where target_type='team_task' and target_id=? and user_id=? and status='pending'", taskId, c)).isEqualTo(1);

        teamTaskService.teamTaskAssigneeTransition(taskId, c, "accepted", List.of("pending"));
        teamTaskService.teamTaskAssigneeTransition(taskId, c, "completed", List.of("accepted"));
        assertThat(count("select count(*) from reminder where target_type='team_task' and target_id=? and status='pending'", taskId)).isZero();
    }

    private long register(String phone, String nickname) {
        return authService.register(phone, "Abc12345", nickname, "Asia/Shanghai");
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

    private static String future(int hours) {
        return OffsetDateTime.now().plusHours(hours).toString();
    }

    private static String past(int hours) {
        return OffsetDateTime.now().minusHours(hours).toString();
    }

    @FunctionalInterface
    private interface ThrowingRunnable {
        void run();
    }
}