package com.dayliane;

import com.dayliane.auth.AuthService;
import com.dayliane.admin.AdminService;
import com.dayliane.common.BusinessException;
import com.dayliane.reminder.ReminderService;
import com.dayliane.notification.NotificationService;
import com.dayliane.home.HomeService;
import com.dayliane.ai.AiService;
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
import java.time.Instant;
import java.sql.Timestamp;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest
@ActiveProfiles("test")
class BusinessAcceptanceTests {

    @Autowired AuthService authService;
    @Autowired AdminService adminService;
    @Autowired ScheduleService scheduleService;
    @Autowired TeamService teamService;
    @Autowired TeamTaskService teamTaskService;
    @Autowired ReminderService reminderService;
    @Autowired NotificationService notificationService;
    @Autowired HomeService homeService;
    @Autowired AiService aiService;
    @Autowired JdbcTemplate jdbc;

    @BeforeEach
    void cleanDatabase() {
        for (String table : List.of("ai_usage_log", "ai_config", "notification", "notification_preference", "reminder", "team_task_assignee", "team_task", "schedule", "task_group", "team_member", "team", "admin_operation_log", "admin_user", "user")) {
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

        String rotatedRefreshToken = text(rotated, "refreshToken");
        authService.logout("Bearer " + text(rotated, "accessToken"), rotatedRefreshToken);
        assertBusinessCode(401, () -> authService.refreshToken(rotatedRefreshToken));

        authService.logout("Bearer " + accessToken);
        assertBusinessCode(401, () -> authService.requireUser("Bearer " + accessToken));

        for (int i = 0; i < 5; i++) {
            assertBusinessCode(401, () -> authService.login("15000000001", "BadPass123", "10.0.0.2"));
        }
        assertBusinessCode(429, () -> authService.login("15000000001", "BadPass123", "10.0.0.2"));
    }

    @Test
    void onlySuperAdminCanManageAdminUsers() {
        jdbc.update("insert into admin_user (username,password_hash,role,status) values ('root','Admin12345','super_admin','active')");
        jdbc.update("insert into admin_user (username,password_hash,role,status) values ('operator','Admin12345','admin','active')");
        long rootId = jdbc.queryForObject("select id from admin_user where username='root'", Long.class);
        long operatorId = jdbc.queryForObject("select id from admin_user where username='operator'", Long.class);

        assertBusinessCode(403, () -> adminService.createAdminUser(operatorId, Map.of(
                "username", "blocked", "password", "Admin12345", "role", "admin"), "127.0.0.1", "test"));
        Map<String, Object> created = adminService.createAdminUser(rootId, Map.of(
                "username", "auditor", "password", "Admin12345", "role", "admin"), "127.0.0.1", "test");
        assertThat(created).containsEntry("role", "admin");
        String auditorToken = text(authService.adminLogin("auditor", "Admin12345", "127.0.0.1"), "accessToken");
        assertThat(authService.requireAdmin("Bearer " + auditorToken)).isEqualTo(id(created));
        assertBusinessCode(403, () -> adminService.adminSetAdminUserStatus(operatorId, id(created), "disabled", "127.0.0.1", "test"));
        assertThat(adminService.adminSetAdminUserStatus(rootId, id(created), "disabled", "127.0.0.1", "test"))
                .containsEntry("status", "disabled");
        assertBusinessCode(401, () -> authService.requireAdmin("Bearer " + auditorToken));
    }

    @Test
    @SuppressWarnings("unchecked")
    void dashboardStatsListSortingAndDisabledUserTokens() {
        jdbc.update("insert into admin_user (username,password_hash,role,status) values ('root','Admin12345','super_admin','active')");
        long rootId = jdbc.queryForObject("select id from admin_user where username='root'", Long.class);
        long owner = register("15000000016", "Owner");
        long member = register("15000000017", "Member");
        String ownerToken = authService.issueAccessToken(owner);

        scheduleService.createSchedule(owner, Map.of("title", "Zulu", "timeType", "deadline_task", "deadlineTime", future(3), "remindAt", future(1)));
        scheduleService.createSchedule(owner, Map.of("title", "Alpha", "timeType", "deadline_task", "deadlineTime", future(2)));
        long teamId = id(teamService.createTeam(owner, "Metrics Team"));
        teamService.joinTeam(member, text(teamService.teamDetail(teamId, owner), "inviteCode"));
        teamTaskService.createTeamTask(owner, Map.of("teamId", teamId, "title", "Task Z", "assigneeUserIds", List.of(member)));
        teamTaskService.createTeamTask(owner, Map.of("teamId", teamId, "title", "Task A", "assigneeUserIds", List.of(member)));

        assertThat(adminService.dashboardStats())
                .containsEntry("users", 2)
                .containsEntry("activeUsers", 2)
                .containsEntry("teams", 1)
                .containsEntry("pendingSchedules", 2)
                .containsEntry("activeTeamTasks", 2)
                .containsEntry("pendingReminders", 1)
                .containsEntry("unreadNotifications", 2);

        List<Map<String, Object>> schedules = (List<Map<String, Object>>) scheduleService
                .listSchedules(owner, 1, 20, null, null, null, null, null, "title_asc").get("list");
        assertThat(schedules).extracting(item -> item.get("title")).containsExactly("Alpha", "Zulu");
        List<Map<String, Object>> tasks = (List<Map<String, Object>>) teamTaskService
                .listTeamTasks(teamId, owner, 1, 20, null, null, null, null, "title_asc").get("list");
        assertThat(tasks).extracting(item -> item.get("title")).containsExactly("Task A", "Task Z");
        assertBusinessCode(400, () -> scheduleService.listSchedules(owner, 1, 20, null, null, null, null, null, "drop_table"));
        assertBusinessCode(400, () -> adminService.adminList("users", 1, 20, null, null, null, null, "passwordHash,asc"));

        adminService.adminSetUserStatus(rootId, owner, "disabled", "127.0.0.1", "test");
        assertBusinessCode(401, () -> authService.requireUser("Bearer " + ownerToken));
        assertThat(adminService.dashboardStats()).containsEntry("activeUsers", 1);
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
                "deadlineTime", future(3),
                "remindAt", future(2)
        )));
        scheduleService.setScheduleStatus(cancelledId, userId, "cancelled");
        assertBusinessCode(400, () -> scheduleService.setScheduleStatus(cancelledId, userId, "completed"));
        assertThat(scheduleService.setScheduleStatus(cancelledId, userId, "pending")).containsEntry("status", "pending");

        long deletedId = id(scheduleService.createSchedule(userId, Map.of(
                "title", "Deleted item", "timeType", "deadline_task", "deadlineTime", future(4), "remindAt", future(2))));
        scheduleService.deleteSchedule(deletedId, userId);
        assertThat(count("select count(*) from reminder where target_type='schedule' and target_id=? and status='cancelled'", deletedId)).isEqualTo(1);
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
        assertThat(teamTaskService.listMyTeamTasks(member, 1, 20, null, null, null, null)).containsEntry("total", 0);
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
        assertThat(teamTaskService.listMyTeamTasks(owner, 1, 20, null, null, null, null)).containsEntry("total", 0);
        assertThat(teamTaskService.listCreatedTeamTasks(owner, 1, 20, null, null, null, null)).containsEntry("total", 1);
        assertThat(teamTaskService.listTeamTasks(teamId, owner, 1, 20, null, null, null, null)).containsEntry("total", 1);
        assertThat(teamTaskService.updateTeamTask(allCompleted, owner, Map.of(
                "title", "All done edited", "description", "Visible to the whole team", "startTime", future(1), "deadlineTime", future(3))))
                .containsEntry("title", "All done edited")
                .containsEntry("description", "Visible to the whole team")
                .containsEntry("canManage", true);
        teamTaskService.teamTaskAssigneeTransition(allCompleted, b, "accepted", List.of("pending"));
        assertThat(teamTaskService.teamTaskAssigneeTransition(allCompleted, b, "completed", List.of("accepted"))).containsEntry("status", "completed");
        assertThat(count("select count(*) from notification where user_id=? and related_type='team_task' and related_id=? and type='task_completed'", owner, allCompleted)).isEqualTo(1);

        long mixed = id(teamTaskService.createTeamTask(owner, Map.of("teamId", teamId, "title", "Mixed result", "assigneeUserIds", List.of(b, c))));
        teamTaskService.teamTaskAssigneeTransition(mixed, b, "accepted", List.of("pending"));
        teamTaskService.teamTaskAssigneeTransition(mixed, b, "completed", List.of("accepted"));
        assertThat(teamTaskService.teamTaskAssigneeTransition(mixed, c, "rejected", List.of("pending"))).containsEntry("status", "completed");
        assertThat(count("select count(*) from notification where user_id=? and related_type='team_task' and related_id=? and type='task_rejected'", owner, mixed)).isEqualTo(1);

        long rejected = id(teamTaskService.createTeamTask(owner, Map.of("teamId", teamId, "title", "Rejected", "assigneeUserIds", List.of(b, c), "deadlineTime", future(2))));
        teamTaskService.teamTaskAssigneeTransition(rejected, b, "rejected", List.of("pending"));
        assertThat(teamTaskService.teamTaskAssigneeTransition(rejected, c, "rejected", List.of("pending"))).containsEntry("status", "all_rejected");
        assertThat(teamTaskService.reassignTeamTask(rejected, owner, b, d)).containsEntry("status", "active");
        assertThat(count("select count(*) from team_task_assignee where task_id=? and user_id=? and is_active=false", rejected, b)).isEqualTo(1);
        assertThat(count("select count(*) from reminder where target_type='team_task' and target_id=? and user_id=? and status='pending'", rejected, d)).isEqualTo(1);

        assertThat(teamTaskService.cancelTeamTask(rejected, owner)).containsEntry("status", "cancelled");
        assertThat(count("select count(*) from notification where user_id=? and related_type='team_task' and related_id=? and type='task_cancelled'", d, rejected)).isEqualTo(1);
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
        assertThat(reminderService.listMyReminders(b, 1, 20, "pending", "team_task")).containsEntry("total", 1);
        teamTaskService.teamTaskAssigneeTransition(taskId, b, "rejected", List.of("pending"));
        assertThat(count("select count(*) from reminder where target_type='team_task' and target_id=? and user_id=? and status='cancelled'", taskId, b)).isEqualTo(1);
        assertThat(count("select count(*) from reminder where target_type='team_task' and target_id=? and user_id=? and status='pending'", taskId, c)).isEqualTo(1);

        teamTaskService.teamTaskAssigneeTransition(taskId, c, "accepted", List.of("pending"));
        teamTaskService.teamTaskAssigneeTransition(taskId, c, "completed", List.of("accepted"));
        assertThat(count("select count(*) from reminder where target_type='team_task' and target_id=? and status='pending'", taskId)).isZero();
    }

    @Test
    void homeTodayAndUpcomingUseUserTimezoneAndIncludeAssignedTasks() {
        long utcUser = authService.register("15000000013", "Abc12345", "UTC", "UTC");
        long laUser = authService.register("15000000014", "Abc12345", "LA", "America/Los_Angeles");
        String deadline = "2026-01-02T01:00:00Z";
        scheduleService.createSchedule(utcUser, Map.of("title", "UTC tomorrow", "timeType", "deadline_task", "deadlineTime", deadline));
        scheduleService.createSchedule(laUser, Map.of("title", "LA today", "timeType", "deadline_task", "deadlineTime", deadline));
        Instant now = Instant.parse("2026-01-01T23:30:00Z");

        assertThat((List<?>) homeService.today(utcUser, now).get("personalSchedules")).isEmpty();
        assertThat((List<?>) homeService.today(laUser, now).get("personalSchedules")).hasSize(1);
        assertThat((List<?>) homeService.upcoming(utcUser, now).get("personalSchedules")).hasSize(1);
        assertThat((List<?>) homeService.upcoming(laUser, now).get("personalSchedules")).isEmpty();

        long teamId = id(teamService.createTeam(utcUser, "Timezone Team"));
        String invite = text(teamService.teamDetail(teamId, utcUser), "inviteCode");
        teamService.joinTeam(laUser, invite);
        teamTaskService.createTeamTask(utcUser, Map.of("teamId", teamId, "title", "Assigned tomorrow", "deadlineTime", deadline, "assigneeUserIds", List.of(utcUser)));
        assertThat((List<?>) homeService.upcoming(utcUser, now).get("teamTasks")).hasSize(1);
    }

    @Test
    void aiPrivacyKeepsMetadataMasksContentAndCleansOldLogs() {
        long userId = register("15000000015", "AI User");
        assertBusinessCode(400, () -> aiService.parseSchedule(userId, "联系 13800138000 和 alice@example.com", false));
        Map<String, Object> metadataOnly = jdbc.queryForMap("select feature_type featureType,input_text inputText,output_text outputText,status from ai_usage_log order by id desc limit 1");
        assertThat(metadataOnly).containsEntry("featureType", "schedule_parse").containsEntry("status", "failed");
        assertThat(metadataOnly.get("inputText")).isNull();
        assertThat(metadataOnly.get("outputText")).isNull();

        assertBusinessCode(400, () -> aiService.parseSchedule(userId, "联系 13800138000 和 alice@example.com", true));
        String storedInput = jdbc.queryForObject("select input_text from ai_usage_log order by id desc limit 1", String.class);
        assertThat(storedInput).contains("138****8000").contains("a***@example.com");
        assertThat(storedInput).doesNotContain("13800138000").doesNotContain("alice@example.com");

        jdbc.update("insert into ai_usage_log (user_id,feature_type,status,created_at) values (?,?,?,?)", userId, "old", "success", Timestamp.from(Instant.now().minus(31, ChronoUnit.DAYS)));
        assertThat(aiService.cleanupExpiredUsageLogs()).isEqualTo(1);
        assertThat(count("select count(*) from ai_usage_log where feature_type='old'")).isZero();
    }

    @Test
    @SuppressWarnings("unchecked")
    void notificationPreferencesAssigneeSummariesAndCompletedSorting() {
        long owner = register("15000000018", "Owner");
        long member = register("15000000019", "Member");
        jdbc.update("update `user` set avatar_url='https://example.com/member.png' where id=?", member);
        assertThat(notificationService.preferences(member))
                .containsEntry("taskAssignedEnabled", true)
                .containsEntry("reminderEnabled", true);
        notificationService.updatePreferences(member, Map.of(
                "browserEnabled", true,
                "taskAssignedEnabled", false,
                "taskStatusEnabled", true,
                "reminderEnabled", false
        ));

        long teamId = id(teamService.createTeam(owner, "Preference Team"));
        teamService.joinTeam(member, text(teamService.teamDetail(teamId, owner), "inviteCode"));
        long taskId = id(teamTaskService.createTeamTask(owner, Map.of(
                "teamId", teamId, "title", "Muted assignment", "assigneeUserIds", List.of(member), "remindAt", past(1))));
        assertThat(count("select count(*) from notification where user_id=? and type='task_assigned'", member)).isZero();
        Map<String, Object> summary = ((List<Map<String, Object>>) teamTaskService
                .listTeamTasks(teamId, owner, 1, 20, null, null, null, null).get("list")).get(0);
        assertThat((List<Map<String, Object>>) summary.get("assignees"))
                .singleElement().satisfies(item -> assertThat(item).containsEntry("avatarUrl", "https://example.com/member.png"));
        assertThat(reminderService.scanReminders()).isZero();
        assertThat(count("select count(*) from reminder where target_type='team_task' and target_id=? and status='cancelled'", taskId)).isEqualTo(1);

        long first = id(scheduleService.createSchedule(owner, Map.of("title", "First", "timeType", "deadline_task", "deadlineTime", future(1))));
        long second = id(scheduleService.createSchedule(owner, Map.of("title", "Second", "timeType", "deadline_task", "deadlineTime", future(2))));
        scheduleService.setScheduleStatus(first, owner, "completed");
        scheduleService.setScheduleStatus(second, owner, "completed");
        scheduleService.sortCompletedSchedules(owner, Map.of("scheduleIds", List.of(second, first)));
        List<Map<String, Object>> completed = (List<Map<String, Object>>) scheduleService
                .listSchedules(owner, 1, 20, "completed", null, null, null, null).get("list");
        assertThat(completed).extracting(item -> item.get("id")).containsExactly(second, first);
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
