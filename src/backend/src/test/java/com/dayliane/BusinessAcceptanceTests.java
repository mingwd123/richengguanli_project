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
import com.dayliane.user.UserService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.time.OffsetDateTime;
import java.time.Instant;
import java.sql.Timestamp;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

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
    @Autowired UserService userService;
    @Autowired JdbcTemplate jdbc;
    @Autowired DataSource dataSource;

    @BeforeEach
    void cleanDatabase() {
        for (String table : List.of("fatigue_alert_log", "fatigue_survey", "fatigue_daily_summary", "user_fatigue_profile", "ai_usage_log", "ai_api_key", "ai_config", "auth_revoked_access_token", "auth_refresh_token", "auth_email_otp", "notification", "reminder_preset", "notification_preference", "reminder", "team_task_event", "team_task_reminder_plan", "team_task_assignee", "team_task", "schedule", "task_group", "team_member", "team", "registration_setting", "admin_operation_log", "admin_user", "user")) {
            jdbc.update("delete from " + ("user".equals(table) ? "`user`" : table));
        }
        jdbc.update("update ai_key_pool_state set revision=0 where id=1");
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
    void passwordChangeInvalidatesExistingAccessAndRefreshTokens() {
        long userId = register("15000000020", "Password User");
        Map<String, Object> login = authService.login("15000000020", "Abc12345", "10.0.0.20");
        String accessToken = text(login, "accessToken");
        String refreshToken = text(login, "refreshToken");

        userService.updatePassword(userId, "Abc12345", "Changed12345");

        assertBusinessCode(401, () -> authService.requireUser("Bearer " + accessToken));
        assertBusinessCode(401, () -> authService.refreshToken(refreshToken));
        assertThat(count("select count(*) from auth_refresh_token where user_id=?", userId)).isZero();
        Map<String, Object> nextLogin = authService.login("15000000020", "Changed12345", "10.0.0.20");
        assertThat(authService.requireUser("Bearer " + text(nextLogin, "accessToken"))).isEqualTo(userId);
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
        assertBusinessCode(400, () -> adminService.createAdminUser(rootId, Map.of(
                "username", "another-root", "password", "Admin12345", "role", "super_admin"), "127.0.0.1", "test"));
        assertBusinessCode(400, () -> adminService.createAdminUser(rootId, Map.of(
                "username", "too-long", "password", "密码密码密码密码密码密码密码密码密码密码密码密码密码密码密码密码密码密码A1", "role", "admin"), "127.0.0.1", "test"));
        String auditorToken = text(authService.adminLogin("auditor", "Admin12345", "127.0.0.1"), "accessToken");
        assertThat(authService.requireAdmin("Bearer " + auditorToken)).isEqualTo(id(created));
        assertBusinessCode(403, () -> adminService.adminSetAdminUserStatus(operatorId, id(created), "disabled", "127.0.0.1", "test"));
        assertThat(adminService.adminSetAdminUserStatus(rootId, id(created), "disabled", "127.0.0.1", "test"))
                .containsEntry("status", "disabled");
        assertBusinessCode(401, () -> authService.requireAdmin("Bearer " + auditorToken));
    }

    @Test
    void regularAndSuperAdminsCanCreateVerifiedUsersWithAuditAndDefaults() {
        jdbc.update("insert into admin_user (username,password_hash,role,status) values ('root','Admin12345','super_admin','active')");
        jdbc.update("insert into admin_user (username,password_hash,role,status) values ('operator','Admin12345','admin','active')");
        jdbc.update("insert into admin_user (username,password_hash,role,status) values ('disabled','Admin12345','admin','disabled')");
        long rootId = jdbc.queryForObject("select id from admin_user where username='root'", Long.class);
        long operatorId = jdbc.queryForObject("select id from admin_user where username='operator'", Long.class);
        long disabledId = jdbc.queryForObject("select id from admin_user where username='disabled'", Long.class);

        Map<String, Object> created = adminService.createUser(operatorId, Map.of(
                "email", "  Managed.User@Example.COM ",
                "password", "User12345",
                "phone", "15100000030",
                "nickname", "Managed User",
                "timezone", "Asia/Shanghai"), "127.0.0.1", "test");
        long createdId = id(created);

        assertThat(created)
                .containsEntry("email", "managed.user@example.com")
                .containsEntry("phone", "15100000030")
                .containsEntry("nickname", "Managed User")
                .containsEntry("status", "active")
                .doesNotContainKeys("password", "passwordHash");
        assertThat(text(created, "emailVerifiedAt")).isNotBlank();
        String passwordHash = jdbc.queryForObject("select password_hash from `user` where id=?", String.class, createdId);
        assertThat(passwordHash).startsWith("$2").doesNotContain("User12345");
        assertThat(count("select count(*) from task_group where user_id=? and scope='personal'", createdId)).isEqualTo(3);
        assertThat(count("select count(*) from admin_operation_log where admin_id=? and action='create_user' and target_id=?", operatorId, createdId)).isEqualTo(1);

        Map<String, Object> login = authService.login("managed.user@example.com", "User12345", "127.0.0.1");
        assertThat(authService.requireUser("Bearer " + text(login, "accessToken"))).isEqualTo(createdId);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> listed = (List<Map<String, Object>>) adminService
                .adminList("users", 1, 20, "managed.user@example.com", null, null, null, null).get("list");
        assertThat(listed).singleElement().satisfies(item ->
                assertThat(item).containsEntry("email", "managed.user@example.com"));
        assertThat(adminService.adminUserDetail(createdId))
                .containsEntry("email", "managed.user@example.com")
                .containsEntry("emailVerifiedAt", created.get("emailVerifiedAt"));

        Map<String, Object> rootCreated = adminService.createUser(rootId, Map.of(
                "email", "root-created@example.com", "password", "User12345"), "127.0.0.1", "test");
        assertThat(rootCreated).containsEntry("nickname", "User").containsEntry("timezone", "Asia/Shanghai");
        assertBusinessCode(403, () -> adminService.createUser(disabledId, Map.of(
                "email", "blocked@example.com", "password", "User12345"), "127.0.0.1", "test"));
        assertBusinessCode(409, () -> adminService.createUser(operatorId, Map.of(
                "email", "managed.user@example.com", "password", "User12345"), "127.0.0.1", "test"));
        assertBusinessCode(409, () -> adminService.createUser(operatorId, Map.of(
                "email", "different@example.com", "phone", "15100000030", "password", "User12345"), "127.0.0.1", "test"));
        assertBusinessCode(400, () -> adminService.createUser(operatorId, Map.of(
                "email", "invalid", "password", "User12345"), "127.0.0.1", "test"));
        assertBusinessCode(400, () -> adminService.createUser(operatorId, Map.of(
                "email", "weak@example.com", "password", "12345678"), "127.0.0.1", "test"));
        assertBusinessCode(400, () -> adminService.createUser(operatorId, Map.of(
                "email", "nickname@example.com", "password", "User12345", "nickname", "Bad\tNickname"), "127.0.0.1", "test"));
        assertBusinessCode(400, () -> adminService.createUser(operatorId, Map.of(
                "email", "timezone@example.com", "password", "User12345", "timezone", "Mars/Olympus"), "127.0.0.1", "test"));
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
        assertThat(count("select count(*) from reminder where target_type='schedule' and target_id=? and status='paused'", scheduleId)).isEqualTo(1);
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
    void teamMemberSummaryIncludesEmailForUsersWithoutPhone() {
        long owner = register("15000000033", "Owner");
        long emailOnlyMember = register("15000000034", "Email Member");
        jdbc.update("update `user` set phone=null,email='member@example.com',email_verified_at=utc_timestamp() where id=?",
                emailOnlyMember);
        Map<String, Object> team = teamService.createTeam(owner, "Email Member Team");
        long teamId = id(team);
        teamService.joinTeam(emailOnlyMember, text(team, "inviteCode"));

        assertThat(teamService.activeMembers(teamId))
                .filteredOn(member -> ((Number) member.get("userId")).longValue() == emailOnlyMember)
                .singleElement()
                .satisfies(member -> {
                    assertThat(member.get("phone")).isNull();
                    assertThat(member).containsEntry("email", "member@example.com");
                });
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
    void memberCreatedTasksRequireManagerApprovalBeforeAssignment() {
        long owner = register("15000000031", "Owner");
        long creator = register("15000000032", "Creator");
        long assignee = register("15000000033", "Assignee");
        long teamId = id(teamService.createTeam(owner, "Approval Team"));
        String invite = text(teamService.teamDetail(teamId, owner), "inviteCode");
        teamService.joinTeam(creator, invite);
        teamService.joinTeam(assignee, invite);

        long taskId = id(teamTaskService.createTeamTask(creator, Map.of(
                "teamId", teamId,
                "title", "Needs approval",
                "assigneeUserIds", List.of(assignee),
                "remindAt", future(2)
        )));
        assertThat(teamTaskService.teamTaskDetail(taskId, creator))
                .containsEntry("status", "pending_approval")
                .containsEntry("approvalStatus", "pending");
        assertThat(count("select count(*) from reminder where target_type='team_task' and target_id=?", taskId)).isZero();
        assertThat(count("select count(*) from notification where user_id=? and type='task_assigned' and related_id=?", assignee, taskId)).isZero();
        assertThat(count("select count(*) from notification where user_id=? and type='task_approval_requested' and related_id=?", owner, taskId)).isEqualTo(1);
        assertThat(teamTaskService.listMyTeamTasks(assignee, 1, 20, null, null, null, null)).containsEntry("total", 0);
        assertBusinessCode(400, () -> teamTaskService.teamTaskAssigneeTransition(taskId, assignee, "accepted", List.of("pending")));

        assertThat(teamTaskService.cancelTeamTask(taskId, creator)).containsEntry("status", "cancelled");
        assertThat(count("select count(*) from notification where user_id=? and type='task_approval_withdrawn' and related_id=?", owner, taskId)).isEqualTo(1);
        assertBusinessCode(400, () -> teamTaskService.approveTeamTask(taskId, owner));
        assertBusinessCode(400, () -> teamTaskService.rejectTeamTaskApproval(taskId, owner));

        assertThat(teamTaskService.restoreTeamTask(taskId, owner)).containsEntry("status", "pending_approval");
        assertThat(teamTaskService.approveTeamTask(taskId, owner))
                .containsEntry("status", "active")
                .containsEntry("approvalStatus", "approved");
        assertThat(count("select count(*) from notification where user_id=? and type='task_assigned' and related_id=?", assignee, taskId)).isEqualTo(1);
        assertThat(count("select count(*) from reminder where target_type='team_task' and target_id=? and user_id=? and status='pending'", taskId, assignee)).isEqualTo(1);
        assertBusinessCode(400, () -> teamTaskService.approveTeamTask(taskId, owner));

        long rejectedId = id(teamTaskService.createTeamTask(creator, Map.of(
                "teamId", teamId,
                "title", "Rejected proposal",
                "assigneeUserIds", List.of(assignee),
                "remindAt", future(3)
        )));
        assertThat(teamTaskService.rejectTeamTaskApproval(rejectedId, owner))
                .containsEntry("status", "approval_rejected")
                .containsEntry("approvalStatus", "rejected");
        assertThat(count("select count(*) from reminder where target_type='team_task' and target_id=?", rejectedId)).isZero();
        assertThat(count("select count(*) from notification where user_id=? and type='task_assigned' and related_id=?", assignee, rejectedId)).isZero();
        assertThat(count("select count(*) from notification where user_id=? and type='task_approval_rejected' and related_id=?", creator, rejectedId)).isEqualTo(1);
        assertBusinessCode(400, () -> teamTaskService.cancelTeamTask(rejectedId, creator));
        assertThat(teamTaskService.resubmitTeamTaskApproval(rejectedId, creator))
                .containsEntry("status", "pending_approval")
                .containsEntry("approvalStatus", "pending");
        assertThat(count("select count(*) from notification where user_id=? and type='task_approval_requested' and related_id=?", owner, rejectedId)).isEqualTo(2);
        assertThat(teamTaskService.approveTeamTask(rejectedId, owner)).containsEntry("status", "active");
        assertThat(count("select count(*) from notification where user_id=? and type='task_assigned' and related_id=?", assignee, rejectedId)).isEqualTo(1);
    }

    @Test
    @SuppressWarnings("unchecked")
    void removedAssigneesCreateRepairableGapsWithoutInvalidatingOtherReminders() {
        long owner = register("15000000034", "Owner");
        long admin = register("15000000035", "Admin");
        long creator = register("15000000036", "Creator");
        long removed = register("15000000037", "Removed");
        long survivor = register("15000000038", "Survivor");
        long replacement = register("15000000039", "Replacement");
        long teamId = id(teamService.createTeam(owner, "Reassignment Team"));
        String invite = text(teamService.teamDetail(teamId, owner), "inviteCode");
        for (long userId : List.of(admin, creator, removed, survivor, replacement)) teamService.joinTeam(userId, invite);
        teamService.changeRole(teamId, admin, owner, "admin");

        long pendingId = id(teamTaskService.createTeamTask(creator, Map.of(
                "teamId", teamId,
                "title", "Pending gap",
                "assigneeUserIds", List.of(removed),
                "remindAt", future(2)
        )));
        long activeId = id(teamTaskService.createTeamTask(owner, Map.of(
                "teamId", teamId,
                "title", "Active gap",
                "assigneeUserIds", List.of(removed, survivor),
                "remindAt", past(1)
        )));

        teamService.removeMember(teamId, removed, admin);
        assertThat(teamTaskService.teamTaskDetail(pendingId, owner))
                .containsEntry("status", "pending_approval")
                .containsEntry("unassignedCount", 1);
        assertThat(count("select count(*) from notification where user_id=? and type='task_unassigned' and related_id=?", owner, pendingId)).isEqualTo(1);
        assertThat(teamTaskService.teamTaskDetail(activeId, owner))
                .containsEntry("status", "unassigned")
                .containsEntry("unassignedCount", 1);
        assertBusinessCode(400, () -> teamTaskService.approveTeamTask(pendingId, owner));
        assertThat(count("select count(*) from reminder where target_type='team_task' and target_id=? and user_id=? and status='cancelled'", activeId, removed)).isEqualTo(1);
        assertThat(count("select count(*) from reminder where target_type='team_task' and target_id=? and user_id=? and status='pending'", activeId, survivor)).isEqualTo(1);
        assertThat(reminderService.scanReminders()).isEqualTo(1);
        assertThat(count("select count(*) from notification where user_id=? and type='reminder' and related_id=?", survivor, activeId)).isEqualTo(1);

        assertThat(teamTaskService.reassignTeamTask(pendingId, owner, removed, replacement))
                .containsEntry("status", "pending_approval")
                .containsEntry("unassignedCount", 0);
        assertThat(count("select count(*) from notification where user_id=? and type='task_assigned' and related_id=?", replacement, pendingId)).isZero();
        assertThat(teamTaskService.approveTeamTask(pendingId, owner)).containsEntry("status", "active");
        assertThat(count("select count(*) from notification where user_id=? and type='task_assigned' and related_id=?", replacement, pendingId)).isEqualTo(1);

        Map<String, Object> repaired = teamTaskService.reassignTeamTask(activeId, owner, removed, replacement);
        assertThat(repaired).containsEntry("status", "active").containsEntry("unassignedCount", 0);
        assertThat((List<Map<String, Object>>) repaired.get("reassignmentCandidates")).isEmpty();
        assertThat(count("select count(*) from team_task_assignee where task_id=? and user_id=? and is_active=true", activeId, replacement)).isEqualTo(1);
    }

    @Test
    void removingRejectedAssigneeDoesNotCreateAFalseVacancy() {
        long owner = register("15000000040", "Owner");
        long rejected = register("15000000041", "Rejected");
        long survivor = register("15000000042", "Survivor");
        long teamId = id(teamService.createTeam(owner, "Rejected Removal Team"));
        String invite = text(teamService.teamDetail(teamId, owner), "inviteCode");
        teamService.joinTeam(rejected, invite);
        teamService.joinTeam(survivor, invite);

        long taskId = id(teamTaskService.createTeamTask(owner, Map.of(
                "teamId", teamId,
                "title", "Rejected member leaves",
                "assigneeUserIds", List.of(rejected, survivor)
        )));
        teamTaskService.teamTaskAssigneeTransition(taskId, rejected, "rejected", List.of("pending"));
        teamTaskService.teamTaskAssigneeTransition(taskId, survivor, "accepted", List.of("pending"));
        teamService.removeMember(teamId, rejected, owner);

        assertThat(teamTaskService.teamTaskDetail(taskId, owner))
                .containsEntry("status", "active")
                .containsEntry("unassignedCount", 0);
        assertThat(teamTaskService.teamTaskAssigneeTransition(taskId, survivor, "completed", List.of("accepted")))
                .containsEntry("status", "completed");
    }

    @Test
    void duplicateActiveAssigneesAreBlockedByServiceAndDatabase() {
        long owner = register("15000000039", "Owner");
        long first = register("15000000040", "First");
        long second = register("15000000041", "Second");
        long teamId = id(teamService.createTeam(owner, "Unique Assignee Team"));
        String invite = text(teamService.teamDetail(teamId, owner), "inviteCode");
        teamService.joinTeam(first, invite);
        teamService.joinTeam(second, invite);

        assertBusinessCode(400, () -> teamTaskService.createTeamTask(owner, Map.of(
                "teamId", teamId, "title", "Duplicate input", "assigneeUserIds", List.of(first, first))));
        long taskId = id(teamTaskService.createTeamTask(owner, Map.of(
                "teamId", teamId, "title", "Unique active", "assigneeUserIds", List.of(first, second))));
        teamTaskService.teamTaskAssigneeTransition(taskId, first, "rejected", List.of("pending"));
        assertBusinessCode(409, () -> teamTaskService.reassignTeamTask(taskId, owner, first, second));

        jdbc.update("insert into team_task_assignee (task_id,user_id,assign_round,is_active,status,assigned_by,assigned_at) values (?,?,2,false,'rejected',?,utc_timestamp())", taskId, first, owner);
        assertThat((List<?>) teamTaskService.teamTaskDetail(taskId, owner).get("reassignmentCandidates")).isEmpty();
        assertThatThrownBy(() -> jdbc.update("insert into team_task_assignee (task_id,user_id,assign_round,is_active,status,assigned_by,assigned_at) values (?,?,3,true,'pending',?,utc_timestamp())", taskId, first, owner))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    @SuppressWarnings("unchecked")
    void adminRestoreAndStatusCorrectionsKeepTaskStateRemindersAndActorIdentityAligned() {
        long owner = register("15000000042", "Same Id User");
        long assignee = register("15000000043", "Assignee");
        long teamId = id(teamService.createTeam(owner, "Admin Repair Team"));
        teamService.joinTeam(assignee, text(teamService.teamDetail(teamId, owner), "inviteCode"));
        jdbc.update("insert into admin_user (id,username,password_hash,role,status) values (?,'root','Admin12345','super_admin','active')", owner);

        long taskId = id(teamTaskService.createTeamTask(owner, Map.of(
                "teamId", teamId,
                "title", "Repairable task",
                "assigneeUserIds", List.of(assignee),
                "remindAt", future(2)
        )));
        assertThat(adminService.adminCancelTeamTask(owner, taskId, "127.0.0.1", "test")).containsEntry("status", "cancelled");
        assertThat(count("select count(*) from reminder where target_type='team_task' and target_id=? and status='paused'", taskId)).isEqualTo(1);
        assertThat(adminService.adminRestoreTeamTask(owner, taskId, "127.0.0.1", "test"))
                .containsEntry("status", "active")
                .containsEntry("approvalStatus", "approved");
        assertThat(count("select count(*) from reminder where target_type='team_task' and target_id=? and status='pending'", taskId)).isEqualTo(1);

        long assigneeId = jdbc.queryForObject("select id from team_task_assignee where task_id=? and user_id=? and is_active=true", Long.class, taskId, assignee);
        teamTaskService.teamTaskAssigneeTransition(taskId, assignee, "accepted", List.of("pending"));
        assertThat(adminService.adminCorrectAssigneeStatus(owner, taskId, assigneeId, "pending", "127.0.0.1", "test"))
                .containsEntry("status", "active");
        assertThat(count("select count(*) from reminder where target_type='team_task' and target_id=? and user_id=? and status='pending'", taskId, assignee)).isEqualTo(1);
        assertThat(adminService.adminCorrectAssigneeStatus(owner, taskId, assigneeId, "accepted", "127.0.0.1", "test"))
                .containsEntry("status", "active");
        assertThat(teamTaskService.correctTeamTaskAssigneeStatus(taskId, assigneeId, owner, "pending"))
                .containsEntry("status", "active");
        assertThat(teamTaskService.correctTeamTaskAssigneeStatus(taskId, assigneeId, owner, "accepted"))
                .containsEntry("status", "active");
        teamTaskService.teamTaskAssigneeTransition(taskId, assignee, "completed", List.of("accepted"));
        assertThat(teamTaskService.teamTaskDetail(taskId, owner)).containsEntry("status", "completed");
        assertBusinessCode(400, () -> teamTaskService.cancelTeamTask(taskId, owner));
        assertBusinessCode(400, () -> adminService.adminCancelTeamTask(owner, taskId, "127.0.0.1", "test"));
        assertBusinessCode(400, () -> teamTaskService.correctTeamTaskAssigneeStatus(taskId, assigneeId, owner, "pending"));
        assertBusinessCode(400, () -> adminService.adminCorrectAssigneeStatus(owner, taskId, assigneeId, "pending", "127.0.0.1", "test"));
        assertBusinessCode(400, () -> teamTaskService.deleteTeamTask(taskId, owner));
        assertThat(count("select count(*) from reminder where target_type='team_task' and target_id=? and status='pending'", taskId)).isZero();

        List<Map<String, Object>> events = (List<Map<String, Object>>) teamTaskService.teamTaskDetail(taskId, owner).get("events");
        assertThat(events).anySatisfy(event -> assertThat(event)
                .containsEntry("actorType", "admin")
                .containsEntry("actorName", "root"));
    }

    @Test
    void concurrentCompletionCannotBeReopenedByUserOrAdminCorrection() throws Exception {
        long owner = register("15000000046", "Terminal Owner");
        long assignee = register("15000000047", "Terminal Assignee");
        long teamId = id(teamService.createTeam(owner, "Terminal Team"));
        teamService.joinTeam(assignee, text(teamService.teamDetail(teamId, owner), "inviteCode"));
        jdbc.update("insert into admin_user (id,username,password_hash,role,status) values (?,'terminal-admin','Admin12345','super_admin','active')", owner);

        long userCorrectionTask = acceptedTask(teamId, owner, assignee, "User correction race");
        long userAssigneeId = activeAssigneeId(userCorrectionTask, assignee);
        assertConcurrentCompletionRejectsCorrection(userCorrectionTask, userAssigneeId,
                () -> teamTaskService.correctTeamTaskAssigneeStatus(userCorrectionTask, userAssigneeId, owner, "pending"));

        long adminCorrectionTask = acceptedTask(teamId, owner, assignee, "Admin correction race");
        long adminAssigneeId = activeAssigneeId(adminCorrectionTask, assignee);
        assertConcurrentCompletionRejectsCorrection(adminCorrectionTask, adminAssigneeId,
                () -> adminService.adminCorrectAssigneeStatus(owner, adminCorrectionTask, adminAssigneeId, "pending", "127.0.0.1", "test"));
    }

    @Test
    @SuppressWarnings("unchecked")
    void teamTaskDateFiltersUseTheRequestingUsersTimezone() {
        long owner = authService.register("15000000044", "Abc12345", "UTC Owner", "UTC");
        long laMember = authService.register("15000000045", "Abc12345", "LA Member", "America/Los_Angeles");
        long teamId = id(teamService.createTeam(owner, "Timezone Filter Team"));
        teamService.joinTeam(laMember, text(teamService.teamDetail(teamId, owner), "inviteCode"));
        long taskId = id(teamTaskService.createTeamTask(owner, Map.of(
                "teamId", teamId,
                "title", "LA January first",
                "assigneeUserIds", List.of(laMember),
                "deadlineTime", "2026-01-02T07:30:00Z"
        )));

        List<Map<String, Object>> laJanuaryFirst = (List<Map<String, Object>>) teamTaskService
                .listMyTeamTasks(laMember, 1, 20, null, null, "2026-01-01", "2026-01-01").get("list");
        assertThat(laJanuaryFirst).extracting(item -> item.get("id")).containsExactly(taskId);
        assertThat(teamTaskService.listMyTeamTasks(laMember, 1, 20, null, null, "2026-01-02", "2026-01-02"))
                .containsEntry("total", 0);
        assertThat(teamTaskService.listTeamTasks(teamId, laMember, 1, 20, null, null, "2026-01-01", "2026-01-01"))
                .containsEntry("total", 1);
        assertThat(teamTaskService.listCreatedTeamTasks(owner, 1, 20, null, null, "2026-01-02", "2026-01-02"))
                .containsEntry("total", 1);
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
    @SuppressWarnings("unchecked")
    void dateRangesIncludeCrossMonthItemsAndRejectInvalidTimes() {
        long userId = authService.register("15000000021", "Abc12345", "Range User", "UTC");
        long scheduleId = id(scheduleService.createSchedule(userId, Map.of(
                "title", "Cross-month schedule",
                "timeType", "duration_task",
                "startTime", "2026-01-31T23:00:00Z",
                "endTime", "2026-02-02T01:00:00Z"
        )));
        List<Map<String, Object>> schedules = (List<Map<String, Object>>) scheduleService
                .listSchedules(userId, 1, 20, null, null, null, "2026-02-01", "2026-02-01").get("list");
        assertThat(schedules).extracting(item -> item.get("id")).containsExactly(scheduleId);

        Map<String, Object> calendar = scheduleService.calendar(userId, 2026, 2);
        List<Map<String, Object>> days = (List<Map<String, Object>>) calendar.get("days");
        Map<String, Object> februaryFirst = days.stream()
                .filter(day -> "2026-02-01".equals(day.get("date")))
                .findFirst().orElseThrow();
        assertThat((List<Map<String, Object>>) februaryFirst.get("schedules"))
                .extracting(item -> item.get("id")).containsExactly(scheduleId);
        assertThat(calendar).containsEntry("timezone", "UTC");

        long teamId = id(teamService.createTeam(userId, "Range Team"));
        long taskId = id(teamTaskService.createTeamTask(userId, Map.of(
                "teamId", teamId,
                "title", "Cross-month task",
                "assigneeUserIds", List.of(userId),
                "startTime", "2026-01-31T23:30:00Z",
                "deadlineTime", "2026-02-02T02:00:00Z"
        )));
        List<Map<String, Object>> tasks = (List<Map<String, Object>>) teamTaskService
                .listMyTeamTasks(userId, 1, 20, null, null, "2026-02-01", "2026-02-01").get("list");
        assertThat(tasks).extracting(item -> item.get("id")).containsExactly(taskId);

        assertBusinessCode(400, () -> scheduleService.createSchedule(userId, Map.of(
                "title", "Invalid schedule", "timeType", "deadline_task", "deadlineTime", "not-a-time")));
        assertBusinessCode(400, () -> scheduleService.createSchedule(userId, Map.of(
                "title", "Reversed schedule", "timeType", "duration_task",
                "startTime", "2026-02-02T10:00:00Z", "endTime", "2026-02-02T09:00:00Z")));
        assertBusinessCode(400, () -> teamTaskService.createTeamTask(userId, Map.of(
                "teamId", teamId, "title", "Invalid task", "deadlineTime", "not-a-time")));
        assertBusinessCode(400, () -> teamTaskService.createTeamTask(userId, Map.of(
                "teamId", teamId, "title", "Reversed task",
                "startTime", "2026-02-02T10:00:00Z", "deadlineTime", "2026-02-02T09:00:00Z")));
        assertBusinessCode(400, () -> scheduleService.calendar(userId, 2026, 13));
    }

    @Test
    void homeTodayIsNotCappedAtOneHundredItems() {
        long userId = authService.register("15000000022", "Abc12345", "Busy User", "UTC");
        Instant now = Instant.parse("2026-03-01T00:00:00Z");
        Timestamp dueAt = Timestamp.from(now.plus(1, ChronoUnit.HOURS));
        for (int i = 0; i < 105; i++) {
            jdbc.update("insert into schedule (user_id,title,time_type,deadline_time,status) values (?,?, 'deadline_task',?, 'pending')",
                    userId, "Schedule " + i, dueAt);
        }

        assertThat((List<?>) homeService.today(userId, now).get("personalSchedules")).hasSize(105);
    }

    @Test
    @SuppressWarnings("unchecked")
    void adminFiltersAndDatabasePagesReturnAccurateTotals() {
        long userId = register("15000000023", "Paged User");
        jdbc.update("insert into admin_user (username,password_hash,role,status) values ('root','Admin12345','super_admin','active')");
        long adminId = jdbc.queryForObject("select id from admin_user where username='root'", Long.class);

        jdbc.update("insert into notification (user_id,type,title,is_read) values (?, 'test','Unread notice',false)", userId);
        jdbc.update("insert into notification (user_id,type,title,is_read) values (?, 'test','Read notice',true)", userId);
        Map<String, Object> unread = adminService.adminList("notifications", 1, 20, null, "unread", null, null);
        assertThat(unread).containsEntry("total", 1);
        assertThat((List<Map<String, Object>>) unread.get("list")).singleElement()
                .satisfies(item -> assertThat(item).containsEntry("title", "Unread notice"));

        jdbc.update("insert into reminder (user_id,target_type,target_id,remind_at,status) values (?, 'schedule',1,?, 'pending')", userId, Timestamp.from(Instant.now()));
        jdbc.update("insert into reminder (user_id,target_type,target_id,remind_at,status) values (?, 'schedule',2,?, 'sent')", userId, Timestamp.from(Instant.now()));
        assertThat(adminService.adminList("reminders", 1, 20, null, "sent", null, null)).containsEntry("total", 1);

        for (int i = 0; i < 3; i++) {
            jdbc.update("insert into admin_operation_log (admin_id,action,target_type) values (?,?,'user')", adminId, "action_" + i);
            jdbc.update("insert into ai_usage_log (user_id,feature_type,status) values (?,'schedule_parse','success')", userId);
            jdbc.update("insert into team (name,invite_code,invite_code_expire_at,owner_id,status) values (?,?,?,?,'active')",
                    "Team " + i, "PAGE0" + i, Timestamp.from(Instant.now().plus(1, ChronoUnit.DAYS)), userId);
            long teamId = jdbc.queryForObject("select id from team where invite_code=?", Long.class, "PAGE0" + i);
            jdbc.update("insert into team_member (team_id,user_id,role,status,joined_at) values (?,?,'owner','active',utc_timestamp())", teamId, userId);
        }

        Map<String, Object> operationLogs = adminService.adminOperationLogs(2, 2, null, null, null, null, null, null);
        assertThat(operationLogs).containsEntry("total", 3).containsEntry("page", 2).containsEntry("size", 2);
        assertThat((List<?>) operationLogs.get("list")).hasSize(1);
        assertBusinessCode(400, () -> adminService.adminOperationLogs(1, 20, "invalid", null, null, null, null, null));

        Map<String, Object> aiLogs = aiService.usageLogs(2, 2, String.valueOf(userId), null, "success", null, null);
        assertThat(aiLogs).containsEntry("total", 3).containsEntry("page", 2).containsEntry("size", 2);
        assertThat((List<?>) aiLogs.get("list")).hasSize(1);

        Map<String, Object> teams = teamService.listTeams(userId, 2, 2);
        assertThat(teams).containsEntry("total", 3).containsEntry("page", 2).containsEntry("size", 2);
        assertThat((List<?>) teams.get("list")).hasSize(1);
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
                "reminderEnabled", false,
                "reminderPresetMinutes", List.of(30, 60, 1440)
        ));
        assertThat(notificationService.preferences(member)).containsEntry("reminderPresetMinutes", List.of(30, 60, 1440));

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

    private long acceptedTask(long teamId, long owner, long assignee, String title) {
        long taskId = id(teamTaskService.createTeamTask(owner, Map.of("teamId", teamId, "title", title, "assigneeUserIds", List.of(assignee))));
        teamTaskService.teamTaskAssigneeTransition(taskId, assignee, "accepted", List.of("pending"));
        return taskId;
    }

    private long activeAssigneeId(long taskId, long userId) {
        return jdbc.queryForObject("select id from team_task_assignee where task_id=? and user_id=? and is_active=true", Long.class, taskId, userId);
    }

    private void assertConcurrentCompletionRejectsCorrection(long taskId, long assigneeId, Runnable correction) throws Exception {
        CompletableFuture<Throwable> correctionResult;
        try (Connection connection = dataSource.getConnection()) {
            connection.setAutoCommit(false);
            try (PreparedStatement lockTask = connection.prepareStatement("select id from team_task where id=? for update");
                 PreparedStatement completeAssignee = connection.prepareStatement("update team_task_assignee set status='completed',completed_at=current_timestamp where id=? and task_id=?");
                 PreparedStatement completeTask = connection.prepareStatement("update team_task set status='completed' where id=?")) {
                lockTask.setLong(1, taskId);
                lockTask.executeQuery();
                completeAssignee.setLong(1, assigneeId);
                completeAssignee.setLong(2, taskId);
                completeAssignee.executeUpdate();
                completeTask.setLong(1, taskId);
                completeTask.executeUpdate();

                CountDownLatch started = new CountDownLatch(1);
                correctionResult = CompletableFuture.supplyAsync(() -> {
                    started.countDown();
                    try {
                        correction.run();
                        return null;
                    } catch (Throwable ex) {
                        return ex;
                    }
                });
                assertThat(started.await(5, TimeUnit.SECONDS)).isTrue();
                Thread.sleep(100);
                assertThat(correctionResult).isNotDone();
                connection.commit();
            } catch (Throwable ex) {
                connection.rollback();
                throw ex;
            }
        }

        Throwable error = correctionResult.get(10, TimeUnit.SECONDS);
        assertThat(error).isInstanceOf(BusinessException.class);
        assertThat(((BusinessException) error).getCode()).isEqualTo(400);
        assertThat(jdbc.queryForObject("select status from team_task where id=?", String.class, taskId)).isEqualTo("completed");
        assertThat(jdbc.queryForObject("select status from team_task_assignee where id=?", String.class, assigneeId)).isEqualTo("completed");
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
