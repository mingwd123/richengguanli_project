package com.dayliane.admin;

import com.dayliane.auth.AuthService;
import com.dayliane.common.BusinessException;
import com.dayliane.user.UserService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@ActiveProfiles("test")
@AutoConfigureMockMvc
class AdminUserUpdateIntegrationTests {
    @Autowired private AdminService adminService;
    @Autowired private AuthService authService;
    @Autowired private UserService userService;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private MockMvc mvc;
    @Autowired private ObjectMapper objectMapper;
    private final BCryptPasswordEncoder passwordEncoder = new BCryptPasswordEncoder();

    @BeforeEach
    void cleanDatabase() {
        for (String table : List.of("fatigue_alert_log", "fatigue_survey_prompt_log", "fatigue_survey_skip",
                "fatigue_survey", "fatigue_daily_summary", "user_fatigue_profile", "auth_revoked_access_token",
                "auth_refresh_token", "auth_email_otp", "task_group", "registration_setting",
                "admin_operation_log", "admin_user", "user")) {
            jdbc.update("delete from " + ("user".equals(table) ? "`user`" : table));
        }
    }

    @Test
    void superAdminCanUpdateIdentityWhileRegistrationIsClosedAndRevokeExistingCredentials() throws Exception {
        long rootId = createAdmin("root", "super_admin", "Root12345", "active");
        Map<String, Object> created = adminService.createUser(rootId, Map.of(
                "email", "old-address@example.com",
                "phone", "15100000031",
                "password", "User12345",
                "nickname", "Before Edit",
                "timezone", "Asia/Shanghai"), "127.0.0.1", "create-test");
        long userId = ((Number) created.get("id")).longValue();
        jdbc.update("update `user` set email_verified_at=? where id=?",
                Timestamp.from(Instant.parse("2020-01-01T00:00:00Z")), userId);
        jdbc.update("insert into registration_setting (id,registration_enabled,updated_by) values (1,false,?)", rootId);
        String passwordHash = jdbc.queryForObject("select password_hash from `user` where id=?", String.class, userId);
        String token = adminToken("root", "Root12345");
        Map<String, Object> userSession = authService.login("old-address@example.com", "User12345", "203.0.113.70");
        String oldAccessToken = String.valueOf(userSession.get("accessToken"));
        String oldRefreshToken = String.valueOf(userSession.get("refreshToken"));
        jdbc.update("insert into auth_email_otp (user_id,email,purpose,code_digest,status,max_attempts,expires_at) "
                        + "values (?,?, 'change_email',?,'issued',5,?)",
                userId, "pending-address@example.com", "a".repeat(64),
                Timestamp.from(Instant.now().plusSeconds(300)));

        Map<String, Object> request = new LinkedHashMap<>();
        request.put("email", "  New.Address@Example.COM ");
        request.put("phone", "");
        request.put("nickname", "");
        request.put("timezone", "UTC");
        request.put("profileVersion", created.get("profileVersion"));
        request.put("status", "disabled");
        request.put("password", "Changed12345");
        mvc.perform(put("/api/v1/admin/users/{id}", userId)
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.email").value("new.address@example.com"))
                .andExpect(jsonPath("$.data.phone").doesNotExist())
                .andExpect(jsonPath("$.data.nickname").value("User"))
                .andExpect(jsonPath("$.data.timezone").value("UTC"))
                .andExpect(jsonPath("$.data.profileVersion").value(1))
                .andExpect(jsonPath("$.data.status").value("active"));

        Map<String, Object> stored = jdbc.queryForMap(
                "select email,phone,nickname,timezone,status,password_hash passwordHash,email_verified_at emailVerifiedAt "
                        + "from `user` where id=?", userId);
        assertThat(stored)
                .containsEntry("email", "new.address@example.com")
                .containsEntry("phone", null)
                .containsEntry("nickname", "User")
                .containsEntry("timezone", "UTC")
                .containsEntry("status", "active")
                .containsEntry("passwordHash", passwordHash);
        assertThat(((Timestamp) stored.get("emailVerifiedAt")).toInstant())
                .isAfter(Instant.parse("2020-01-01T00:00:00Z"));
        assertThat(jdbc.queryForObject("select token_version from `user` where id=?", Integer.class, userId))
                .isEqualTo(1);
        assertThat(jdbc.queryForObject("select profile_version from `user` where id=?", Long.class, userId))
                .isEqualTo(1L);
        assertThat(jdbc.queryForObject("select count(*) from auth_refresh_token where user_id=?", Integer.class, userId))
                .isZero();
        assertThat(jdbc.queryForObject("select status from auth_email_otp where user_id=?", String.class, userId))
                .isEqualTo("invalidated");

        assertBusinessCode(401, () -> authService.requireUser("Bearer " + oldAccessToken));
        assertBusinessCode(401, () -> authService.refreshToken(oldRefreshToken));
        assertBusinessCode(401, () -> authService.login("old-address@example.com", "User12345", "203.0.113.71"));
        assertThat(authService.login("new.address@example.com", "User12345", "203.0.113.72"))
                .containsEntry("userId", userId);

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> logs = (List<Map<String, Object>>) adminService.adminOperationLogs(
                1, 20, String.valueOf(rootId), "update_user", "user", null, null, null).get("list");
        assertThat(logs).singleElement().satisfies(log -> {
            assertThat(log)
                    .containsEntry("targetId", userId)
                    .containsEntry("ipAddress", "127.0.0.1")
                    .containsEntry("userAgent", null);
            assertThat(String.valueOf(log.get("beforeData")))
                    .contains("old-address@example.com")
                    .doesNotContain("passwordHash");
            assertThat(String.valueOf(log.get("afterData")))
                    .contains("new.address@example.com")
                    .doesNotContain("passwordHash");
        });
    }

    @Test
    void regularAdminCanUpdateNicknameAndTimezoneButCannotChangeLoginIdentifiers() throws Exception {
        long operatorId = createAdmin("operator", "admin", "Admin12345", "active");
        long userId = createManagedUser(operatorId, "profile@example.com", "15100000035");
        String passwordHash = jdbc.queryForObject("select password_hash from `user` where id=?", String.class, userId);
        String token = adminToken("operator", "Admin12345");

        mvc.perform(put("/api/v1/admin/users/{id}", userId)
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "email", "profile@example.com",
                                "phone", "15100000035",
                                "nickname", "",
                                "timezone", "Asia/Tokyo",
                                "profileVersion", 0,
                                "status", "disabled",
                                "password", "Changed12345"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.nickname").value("User"))
                .andExpect(jsonPath("$.data.timezone").value("Asia/Tokyo"))
                .andExpect(jsonPath("$.data.email").value("profile@example.com"))
                .andExpect(jsonPath("$.data.phone").value("15100000035"))
                .andExpect(jsonPath("$.data.profileVersion").value(1))
                .andExpect(jsonPath("$.data.status").value("active"));

        assertThat(jdbc.queryForMap("select status,password_hash passwordHash,token_version tokenVersion "
                + "from `user` where id=?", userId))
                .containsEntry("status", "active")
                .containsEntry("passwordHash", passwordHash)
                .containsEntry("tokenVersion", 0);
        assertBusinessCode(403, () -> update(operatorId, userId,
                "changed-by-operator@example.com", "15100000035", "User", "Asia/Tokyo"));
        assertBusinessCode(403, () -> update(operatorId, userId,
                "profile@example.com", "15100000036", "User", "Asia/Tokyo"));
        assertThat(adminService.userView(userId))
                .containsEntry("email", "profile@example.com")
                .containsEntry("phone", "15100000035");
    }

    @Test
    void superAdminCanUpdateLegacyPhoneUserAndEmailVerificationFollowsEmailState() {
        long rootId = createAdmin("root", "super_admin", "Root12345", "active");
        long userId = insertLegacyPhoneUser("15100000032", "Legacy12345");

        Map<String, Object> bound = adminService.updateUser(rootId, userId, Map.of(
                "email", "legacy@example.com",
                "phone", "15100000032",
                "nickname", "Legacy User",
                "timezone", "Asia/Shanghai",
                "profileVersion", 0), "127.0.0.1", "test");
        assertThat(bound)
                .containsEntry("email", "legacy@example.com")
                .containsEntry("phone", "15100000032");
        assertThat(String.valueOf(bound.get("emailVerifiedAt"))).isNotBlank();
        Timestamp verifiedAt = jdbc.queryForObject("select email_verified_at from `user` where id=?", Timestamp.class, userId);

        Map<String, Object> unchanged = adminService.updateUser(rootId, userId, Map.of(
                "email", "legacy@example.com",
                "phone", "15100000032",
                "nickname", "Renamed Legacy",
                "timezone", "Asia/Shanghai",
                "profileVersion", 1), "127.0.0.1", "test");
        assertThat(unchanged).containsEntry("nickname", "Renamed Legacy");
        assertThat(jdbc.queryForObject("select email_verified_at from `user` where id=?", Timestamp.class, userId))
                .isEqualTo(verifiedAt);

        Map<String, Object> cleared = adminService.updateUser(rootId, userId, Map.of(
                "email", "",
                "phone", "15100000032",
                "nickname", "Phone Login Only",
                "timezone", "Asia/Shanghai",
                "profileVersion", 2), "127.0.0.1", "test");
        assertThat(cleared).containsEntry("email", null).containsEntry("phone", "15100000032");
        assertThat(jdbc.queryForObject("select email_verified_at from `user` where id=?", Timestamp.class, userId))
                .isNull();
        assertThat(authService.login("15100000032", "Legacy12345", "203.0.113.73"))
                .containsEntry("userId", userId);
    }

    @Test
    void updateRejectsDuplicateInvalidMissingAndUnauthorizedRequests() {
        long rootId = createAdmin("root", "super_admin", "Root12345", "active");
        long operatorId = createAdmin("operator", "admin", "Admin12345", "active");
        long disabledId = createAdmin("disabled", "admin", "Admin12345", "disabled");
        long first = createManagedUser(operatorId, "first@example.com", "15100000033");
        createManagedUser(operatorId, "second@example.com", "15100000034");

        assertBusinessCode(409, () -> update(rootId, first, "second@example.com", "15100000033", "First", "UTC"));
        assertBusinessCode(409, () -> update(rootId, first, "first@example.com", "15100000034", "First", "UTC"));
        assertBusinessCode(400, () -> update(rootId, first, "invalid", "15100000033", "First", "UTC"));
        assertBusinessCode(400, () -> update(rootId, first, "first@example.com", "123", "First", "UTC"));
        assertBusinessCode(400, () -> update(rootId, first, "first@example.com", "15100000033", "Bad\tName", "UTC"));
        assertBusinessCode(400, () -> update(rootId, first, "first@example.com", "15100000033", "x".repeat(51), "UTC"));
        assertBusinessCode(400, () -> update(rootId, first, "first@example.com", "15100000033", "First", "Mars/Olympus"));
        assertBusinessCode(400, () -> update(rootId, first, "", "", "First", "UTC"));
        assertBusinessCode(404, () -> updateWithVersion(
                rootId, 999999L, "missing@example.com", "", "Missing", "UTC", 0));
        assertBusinessCode(403, () -> update(disabledId, first, "first@example.com", "15100000033", "First", "UTC"));
        assertThat(jdbc.queryForObject("select count(*) from admin_operation_log where action='update_user'", Integer.class))
                .isZero();
    }

    @Test
    void emptyTimezoneUsesDefaultAndExactNoOpDoesNotWriteAuditLog() {
        long operatorId = createAdmin("operator", "admin", "Admin12345", "active");
        long userId = createManagedUser(operatorId, "defaults@example.com", "15100000038");

        Map<String, Object> defaulted = update(operatorId, userId,
                "defaults@example.com", "15100000038", "Managed", "");
        assertThat(defaulted).containsEntry("timezone", "Asia/Shanghai");
        assertThat(jdbc.queryForObject("select count(*) from admin_operation_log where action='update_user'", Integer.class))
                .isEqualTo(1);

        Map<String, Object> unchanged = update(operatorId, userId,
                "defaults@example.com", "15100000038", "Managed", "Asia/Shanghai");
        assertThat(unchanged).containsEntry("timezone", "Asia/Shanghai");
        assertThat(jdbc.queryForObject("select count(*) from admin_operation_log where action='update_user'", Integer.class))
                .isEqualTo(1);
    }

    @Test
    void staleAndInvalidProfileVersionsAreRejectedAndSelfEditsAdvanceTheVersion() {
        long rootId = createAdmin("root", "super_admin", "Root12345", "active");
        long userId = createManagedUser(rootId, "versioned@example.com", "15100000039");

        assertThat(adminService.adminUserDetail(userId)).containsEntry("profileVersion", 0L);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> users = (List<Map<String, Object>>) adminService.adminList(
                "users", 1, 20, "versioned@example.com", null, null, null, null).get("list");
        assertThat(users).singleElement().satisfies(user -> assertThat(user).containsEntry("profileVersion", 0L));

        userService.updateUserProfile(userId, Map.of("nickname", "Self Renamed"));
        assertThat(profileVersion(userId)).isEqualTo(1L);
        assertBusinessMessage(409, "user information changed", () -> updateWithVersion(
                rootId, userId, "versioned@example.com", "15100000039", "Admin Rename", "UTC", 0));

        userService.updateTimezone(userId, "Asia/Tokyo");
        assertThat(profileVersion(userId)).isEqualTo(2L);

        Map<String, Object> missing = profileRequest(
                "versioned@example.com", "15100000039", "Admin Rename", "Asia/Tokyo", 2);
        missing.remove("profileVersion");
        assertBusinessCode(400, () -> adminService.updateUser(rootId, userId, missing,
                "127.0.0.1", "test"));
        for (Object invalid : List.of(-1, 1.5, "2")) {
            Map<String, Object> request = profileRequest(
                    "versioned@example.com", "15100000039", "Admin Rename", "Asia/Tokyo", invalid);
            assertBusinessCode(400, () -> adminService.updateUser(rootId, userId, request,
                    "127.0.0.1", "test"));
        }
        assertThat(profileVersion(userId)).isEqualTo(2L);
    }

    @Test
    void untrustedOversizedForwardedAddressFallsBackToBoundedRemoteAddress() throws Exception {
        long operatorId = createAdmin("operator", "admin", "Admin12345", "active");
        long userId = createManagedUser(operatorId, "audit-ip@example.com", "15100000040");
        String token = adminToken("operator", "Admin12345");

        mvc.perform(put("/api/v1/admin/users/{id}", userId)
                        .with(request -> {
                            request.setRemoteAddr("198.51.100.42");
                            return request;
                        })
                        .header("Authorization", "Bearer " + token)
                        .header("X-Forwarded-For", "9".repeat(200))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(profileRequest(
                                "audit-ip@example.com", "15100000040", "Audited", "UTC", 0))))
                .andExpect(status().isOk());

        assertThat(jdbc.queryForObject("select ip_address from admin_operation_log "
                + "where action='update_user' and target_id=?", String.class, userId))
                .isEqualTo("198.51.100.42");
    }

    @Test
    void disablingUserRevokesSessionsAndOldAccessTokenCannotReviveAfterReenable() {
        long rootId = createAdmin("root", "super_admin", "Root12345", "active");
        long userId = createManagedUser(rootId, "disable-session@example.com", "15100000037");
        Map<String, Object> session = authService.login(
                "disable-session@example.com", "User12345", "203.0.113.74");
        String accessToken = String.valueOf(session.get("accessToken"));
        String refreshToken = String.valueOf(session.get("refreshToken"));

        adminService.adminSetUserStatus(rootId, userId, "disabled", "127.0.0.1", "test");
        assertThat(jdbc.queryForObject("select token_version from `user` where id=?", Integer.class, userId))
                .isEqualTo(1);
        assertThat(jdbc.queryForObject("select count(*) from auth_refresh_token where user_id=?", Integer.class, userId))
                .isZero();
        assertBusinessCode(401, () -> authService.requireUser("Bearer " + accessToken));
        assertBusinessCode(401, () -> authService.refreshToken(refreshToken));

        adminService.adminSetUserStatus(rootId, userId, "active", "127.0.0.1", "test");
        assertBusinessCode(401, () -> authService.requireUser("Bearer " + accessToken));
        assertThat(authService.login("disable-session@example.com", "User12345", "203.0.113.75"))
                .containsEntry("userId", userId);
        assertThat(jdbc.queryForObject("select count(*) from admin_operation_log "
                + "where action='set_user_status' and target_id=?", Integer.class, userId)).isEqualTo(2);
    }

    private long createAdmin(String username, String role, String password, String status) {
        jdbc.update("insert into admin_user (username,password_hash,role,status) values (?,?,?,?)",
                username, passwordEncoder.encode(password), role, status);
        return jdbc.queryForObject("select id from admin_user where username=?", Long.class, username);
    }

    private String adminToken(String username, String password) {
        return String.valueOf(authService.adminLogin(username, password, "127.0.0.1").get("accessToken"));
    }

    private long createManagedUser(long adminId, String email, String phone) {
        return ((Number) adminService.createUser(adminId, Map.of(
                "email", email,
                "phone", phone,
                "password", "User12345",
                "nickname", "Managed",
                "timezone", "UTC"), "127.0.0.1", "test").get("id")).longValue();
    }

    private long insertLegacyPhoneUser(String phone, String password) {
        jdbc.update("insert into `user` (phone,password_hash,nickname,avatar_url,timezone,status) values (?,?, 'Legacy','', 'Asia/Shanghai','active')",
                phone, passwordEncoder.encode(password));
        return jdbc.queryForObject("select id from `user` where phone=?", Long.class, phone);
    }

    private Map<String, Object> update(long adminId, long userId, String email, String phone,
                                       String nickname, String timezone) {
        return updateWithVersion(adminId, userId, email, phone, nickname, timezone, profileVersion(userId));
    }

    private Map<String, Object> updateWithVersion(long adminId, long userId, String email, String phone,
                                                  String nickname, String timezone, Object profileVersion) {
        return adminService.updateUser(adminId, userId,
                profileRequest(email, phone, nickname, timezone, profileVersion), "127.0.0.1", "test");
    }

    private static Map<String, Object> profileRequest(String email, String phone, String nickname,
                                                      String timezone, Object profileVersion) {
        Map<String, Object> request = new LinkedHashMap<>();
        request.put("email", email);
        request.put("phone", phone);
        request.put("nickname", nickname);
        request.put("timezone", timezone);
        request.put("profileVersion", profileVersion);
        return request;
    }

    private long profileVersion(long userId) {
        return jdbc.queryForObject("select profile_version from `user` where id=?", Long.class, userId);
    }

    private static void assertBusinessCode(int expectedCode, ThrowingRunnable runnable) {
        assertThatThrownBy(runnable::run)
                .isInstanceOf(BusinessException.class)
                .extracting(ex -> ((BusinessException) ex).getCode())
                .isEqualTo(expectedCode);
    }

    private static void assertBusinessMessage(int expectedCode, String expectedMessage, ThrowingRunnable runnable) {
        assertThatThrownBy(runnable::run)
                .isInstanceOf(BusinessException.class)
                .satisfies(ex -> {
                    assertThat(((BusinessException) ex).getCode()).isEqualTo(expectedCode);
                    assertThat(ex).hasMessage(expectedMessage);
                });
    }

    @FunctionalInterface
    private interface ThrowingRunnable {
        void run();
    }
}
