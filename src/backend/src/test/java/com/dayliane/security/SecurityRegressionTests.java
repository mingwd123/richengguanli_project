package com.dayliane.security;

import com.dayliane.ai.AdminAiController;
import com.dayliane.ai.AiService;
import com.dayliane.auth.AuthService;
import com.dayliane.common.BusinessException;
import com.dayliane.common.GlobalExceptionHandler;
import com.dayliane.config.DemoAccountInitializer;
import com.dayliane.user.UserService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import javax.sql.DataSource;
import java.net.http.HttpClient;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@ActiveProfiles("test")
class SecurityRegressionTests {

    @Autowired AuthService authService;
    @Autowired UserService userService;
    @Autowired AdminAiController adminAiController;
    @Autowired AiService aiService;
    @Autowired JdbcTemplate jdbc;
    @Autowired DataSource dataSource;
    @Autowired PlatformTransactionManager transactionManager;

    private final BCryptPasswordEncoder passwordEncoder = new BCryptPasswordEncoder();

    @BeforeEach
    void cleanDatabase() {
        for (String table : List.of("ai_usage_log", "ai_config", "team_task_event", "team_task_reminder_plan",
                "team_task_assignee", "team_task",
                "auth_revoked_access_token", "auth_refresh_token",
                "admin_operation_log", "admin_user", "task_group", "user")) {
            jdbc.update("delete from " + ("user".equals(table) ? "`user`" : table));
        }
    }

    @Test
    void refreshTokenIsConsumedExactlyOnceUnderConcurrency() throws Exception {
        long userId = authService.register("15100000001", "Abc12345", "Concurrent User", "UTC");
        String refreshToken = String.valueOf(authService.login("15100000001", "Abc12345", "10.1.0.1").get("refreshToken"));
        int workers = 6;
        ExecutorService executor = Executors.newFixedThreadPool(workers);
        CountDownLatch ready = new CountDownLatch(workers);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<Integer>> futures = new ArrayList<>();
        try {
            for (int i = 0; i < workers; i++) {
                futures.add(executor.submit(() -> {
                    ready.countDown();
                    if (!start.await(5, TimeUnit.SECONDS)) return 500;
                    try {
                        authService.refreshToken(refreshToken);
                        return 0;
                    } catch (BusinessException ex) {
                        return ex.getCode();
                    }
                }));
            }
            assertThat(ready.await(5, TimeUnit.SECONDS)).isTrue();
            start.countDown();
            List<Integer> results = new ArrayList<>();
            for (Future<Integer> future : futures) results.add(future.get(10, TimeUnit.SECONDS));
            assertThat(results).filteredOn(code -> code == 0).hasSize(1);
            assertThat(results).filteredOn(code -> code == 401).hasSize(workers - 1);
            assertThat(jdbc.queryForObject("select count(*) from auth_refresh_token where user_id=?", Integer.class, userId))
                    .isEqualTo(1);
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void plaintextPasswordRowsAreNeverAcceptedByAuthentication() {
        jdbc.update("insert into `user` (phone,password_hash,nickname,timezone,status,token_version) values (?,?,?,?,?,0)",
                "15100000002", "Abc12345", "Legacy User", "UTC", "active");
        long userId = jdbc.queryForObject("select id from `user` where phone=?", Long.class, "15100000002");
        jdbc.update("insert into admin_user (username,password_hash,role,status) values (?,?,?,?)",
                "legacy-admin", "Admin12345", "super_admin", "active");

        assertBusinessCode(401, () -> authService.login("15100000002", "Abc12345", "10.1.0.2"));
        assertBusinessCode(401, () -> authService.adminLogin("legacy-admin", "Admin12345", "10.1.0.3"));
        assertBusinessCode(400, () -> userService.updatePassword(userId, "Abc12345", "Changed12345"));
    }

    @Test
    void hardeningMigrationDisablesExistingPlaintextSeedAccounts() {
        jdbc.update("insert into `user` (phone,password_hash,nickname,timezone,status,token_version) values (?,?,?,?,?,0)",
                "13800138000", "Abc12345", "Demo User", "UTC", "active");
        long userId = jdbc.queryForObject("select id from `user` where phone='13800138000'", Long.class);
        jdbc.update("insert into auth_refresh_token (jti,user_id,expires_at) values (?,?,dateadd('DAY',1,current_timestamp))",
                "seed-refresh-token", userId);
        jdbc.update("insert into admin_user (username,password_hash,role,status) values (?,?,?,?)",
                "admin", "Admin12345", "super_admin", "active");

        new ResourceDatabasePopulator(new ClassPathResource("db/migration/V11__harden_seed_credentials.sql"))
                .execute(dataSource);

        Map<String, Object> user = jdbc.queryForMap("select password_hash passwordHash,status,token_version tokenVersion from `user` where id=?", userId);
        Map<String, Object> admin = jdbc.queryForMap("select password_hash passwordHash,status from admin_user where username='admin'");
        assertThat(user).containsEntry("status", "disabled").containsEntry("tokenVersion", 1);
        assertThat(passwordEncoder.matches("Abc12345", String.valueOf(user.get("passwordHash")))).isFalse();
        assertThat(admin).containsEntry("status", "disabled");
        assertThat(passwordEncoder.matches("Admin12345", String.valueOf(admin.get("passwordHash")))).isFalse();
        assertThat(jdbc.queryForObject("select count(*) from auth_refresh_token", Integer.class)).isZero();
    }

    @Test
    void devInitializerOnlyRestoresKnownSeedAccountsWithBcrypt() {
        jdbc.update("insert into `user` (phone,password_hash,nickname,timezone,status,token_version) values (?,?,?,?,?,0)",
                "13800138000", "Abc12345", "Demo User", "UTC", "disabled");
        jdbc.update("insert into `user` (phone,password_hash,nickname,timezone,status,token_version) values (?,?,?,?,?,0)",
                "15100000009", "Abc12345", "Unrelated User", "UTC", "disabled");
        jdbc.update("insert into admin_user (username,password_hash,role,status) values (?,?,?,?)",
                "admin", "Admin12345", "super_admin", "disabled");

        new DemoAccountInitializer(jdbc, environment("dev"), true, transactionManager).afterPropertiesSet();

        Map<String, Object> demo = jdbc.queryForMap("select password_hash passwordHash,status from `user` where phone='13800138000'");
        Map<String, Object> unrelated = jdbc.queryForMap("select password_hash passwordHash,status from `user` where phone='15100000009'");
        Map<String, Object> admin = jdbc.queryForMap("select password_hash passwordHash,status from admin_user where username='admin'");
        assertThat(passwordEncoder.matches("Abc12345", String.valueOf(demo.get("passwordHash")))).isTrue();
        assertThat(demo).containsEntry("status", "active");
        assertThat(unrelated).containsEntry("passwordHash", "Abc12345").containsEntry("status", "disabled");
        assertThat(passwordEncoder.matches("Admin12345", String.valueOf(admin.get("passwordHash")))).isTrue();
        assertThat(admin).containsEntry("status", "active");
    }

    @Test
    void productionStartupDisablesDevDemoCredentialsAndRevokesRefreshTokens() {
        jdbc.update("insert into `user` (phone,password_hash,nickname,timezone,status,token_version) values (?,?,?,?,?,0)",
                "13800138000", "Abc12345", "Demo User", "UTC", "disabled");
        jdbc.update("insert into admin_user (username,password_hash,role,status) values (?,?,?,?)",
                "admin", "Admin12345", "super_admin", "disabled");
        new DemoAccountInitializer(jdbc, environment("dev"), true, transactionManager).afterPropertiesSet();

        String refreshToken = String.valueOf(authService.login("13800138000", "Abc12345", "10.1.0.4").get("refreshToken"));
        authService.adminLogin("admin", "Admin12345", "10.1.0.5");
        assertThat(jdbc.queryForObject("select count(*) from auth_refresh_token", Integer.class)).isEqualTo(1);

        new DemoAccountInitializer(jdbc, environment("prod"), true, transactionManager).afterPropertiesSet();

        Map<String, Object> user = jdbc.queryForMap("select password_hash passwordHash,status,token_version tokenVersion from `user` where phone='13800138000'");
        Map<String, Object> admin = jdbc.queryForMap("select password_hash passwordHash,status from admin_user where username='admin'");
        assertThat(user).containsEntry("status", "disabled");
        assertThat(passwordEncoder.matches("Abc12345", String.valueOf(user.get("passwordHash")))).isFalse();
        assertThat(((Number) user.get("tokenVersion")).intValue()).isGreaterThan(0);
        assertThat(admin).containsEntry("status", "disabled");
        assertThat(passwordEncoder.matches("Admin12345", String.valueOf(admin.get("passwordHash")))).isFalse();
        assertThat(jdbc.queryForObject("select count(*) from auth_refresh_token", Integer.class)).isZero();
        assertBusinessCode(401, () -> authService.login("13800138000", "Abc12345", "10.1.0.4"));
        assertBusinessCode(401, () -> authService.refreshToken(refreshToken));
        assertBusinessCode(401, () -> authService.adminLogin("admin", "Admin12345", "10.1.0.5"));
    }

    @Test
    void aiConfigurationRequiresSuperAdminAndRejectsUnsafeEndpoints() {
        jdbc.update("insert into admin_user (username,password_hash,role,status) values (?,?,?,?)",
                "root", passwordEncoder.encode("Admin12345"), "super_admin", "active");
        jdbc.update("insert into admin_user (username,password_hash,role,status) values (?,?,?,?)",
                "operator", passwordEncoder.encode("Admin12345"), "admin", "active");
        MockHttpServletRequest rootRequest = authorizedAdminRequest("root");
        MockHttpServletRequest operatorRequest = authorizedAdminRequest("operator");
        Map<String, Object> config = Map.of(
                "provider", "test", "modelName", "test-model", "apiBaseUrl", "https://8.8.8.8/v1", "enabled", false);

        assertBusinessCode(403, () -> adminAiController.config(operatorRequest));
        assertBusinessCode(403, () -> adminAiController.updateConfig(operatorRequest, config));
        assertBusinessCode(403, () -> adminAiController.updateEnabled(operatorRequest, Map.of("enabled", true)));
        assertBusinessCode(403, () -> adminAiController.test(operatorRequest));

        assertBusinessCode(400, () -> adminAiController.updateConfig(rootRequest, Map.of(
                "provider", "test", "modelName", "test-model", "apiBaseUrl", "http://8.8.8.8/v1")));
        assertBusinessCode(400, () -> adminAiController.updateConfig(rootRequest, Map.of(
                "provider", "test", "modelName", "test-model", "apiBaseUrl", "https://8.8.8.8:8443/v1")));
        assertBusinessCode(400, () -> adminAiController.updateConfig(rootRequest, Map.of(
                "provider", "test", "modelName", "test-model", "apiBaseUrl", "https://127.0.0.1/v1")));
        assertBusinessCode(400, () -> adminAiController.updateConfig(rootRequest, Map.of(
                "provider", "test", "modelName", "test-model", "apiBaseUrl", "https://example.com/v1")));

        assertThat(adminAiController.updateConfig(rootRequest, Map.of(
                "provider", "test", "modelName", "test-model", "apiBaseUrl", "https://8.8.8.8:443/v1", "enabled", false)).data())
                .containsEntry("apiBaseUrl", "https://8.8.8.8:443/v1");
        assertThat(adminAiController.updateConfig(rootRequest, config).data())
                .containsEntry("apiBaseUrl", "https://8.8.8.8/v1");
        HttpClient httpClient = (HttpClient) ReflectionTestUtils.getField(aiService, "httpClient");
        assertThat(httpClient).isNotNull();
        assertThat(httpClient.followRedirects()).isEqualTo(HttpClient.Redirect.NEVER);
    }

    @Test
    void dailyAiContextKeepsUnassignedTasksForRemainingAssignees() {
        long userId = authService.register("15100000010", "Abc12345", "Remaining Assignee", "UTC");
        jdbc.update("insert into team_task (team_id,creator_id,title,status,approval_status,unassigned_count) values (?,?,?,?,?,?)",
                9001, userId, "Visible unassigned task", "unassigned", "approved", 1);
        long visibleTaskId = jdbc.queryForObject("select id from team_task where title='Visible unassigned task'", Long.class);
        jdbc.update("insert into team_task_assignee (task_id,user_id,is_active,status) values (?,?,true,'accepted')",
                visibleTaskId, userId);
        jdbc.update("insert into team_task (team_id,creator_id,title,status,approval_status) values (?,?,?,?,?)",
                9001, userId, "Hidden pending approval task", "pending_approval", "pending");
        long hiddenTaskId = jdbc.queryForObject("select id from team_task where title='Hidden pending approval task'", Long.class);
        jdbc.update("insert into team_task_assignee (task_id,user_id,is_active,status) values (?,?,true,'pending')",
                hiddenTaskId, userId);
        jdbc.update("insert into team_task (team_id,creator_id,title,status,approval_status) values (?,?,?,?,?)",
                9001, userId, "Rejected assignment task", "active", "approved");
        long rejectedTaskId = jdbc.queryForObject("select id from team_task where title='Rejected assignment task'", Long.class);
        jdbc.update("insert into team_task_assignee (task_id,user_id,is_active,status) values (?,?,true,'rejected')",
                rejectedTaskId, userId);
        jdbc.update("insert into team_task (team_id,creator_id,title,status,approval_status) values (?,?,?,?,?)",
                9001, userId, "Completed assignment task", "active", "approved");
        long completedTaskId = jdbc.queryForObject("select id from team_task where title='Completed assignment task'", Long.class);
        jdbc.update("insert into team_task_assignee (task_id,user_id,is_active,status) values (?,?,true,'completed')",
                completedTaskId, userId);

        String context = ReflectionTestUtils.invokeMethod(aiService, "dailyContext", userId);

        assertThat(context).contains("Visible unassigned task");
        assertThat(context).doesNotContain("Hidden pending approval task");
        assertThat(context).doesNotContain("Rejected assignment task");
        assertThat(context).doesNotContain("Completed assignment task");
    }

    @Test
    void exceptionHandlerUsesHttpStatusAndDoesNotExposeUnexpectedDetails() throws Exception {
        MockMvc mvc = MockMvcBuilders.standaloneSetup(new ThrowingController())
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();

        mvc.perform(get("/security-test/business"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value(403));
        mvc.perform(post("/security-test/business"))
                .andExpect(status().isMethodNotAllowed())
                .andExpect(jsonPath("$.code").value(405));
        mvc.perform(get("/security-test/invalid-business"))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.code").value(500))
                .andExpect(content().string(not(containsString("invalid-business-secret"))));
        mvc.perform(get("/security-test/unexpected"))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.code").value(500))
                .andExpect(content().string(not(containsString("database-password=secret"))));
    }

    private MockHttpServletRequest authorizedAdminRequest(String username) {
        String token = String.valueOf(authService.adminLogin(username, "Admin12345", "127.0.0.1").get("accessToken"));
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader("Authorization", "Bearer " + token);
        request.setRemoteAddr("127.0.0.1");
        return request;
    }

    private static MockEnvironment environment(String profile) {
        MockEnvironment environment = new MockEnvironment();
        environment.setActiveProfiles(profile);
        return environment;
    }

    private static void assertBusinessCode(int code, ThrowingRunnable runnable) {
        assertThatThrownBy(runnable::run)
                .isInstanceOf(BusinessException.class)
                .extracting(ex -> ((BusinessException) ex).getCode())
                .isEqualTo(code);
    }

    @RestController
    @RequestMapping("/security-test")
    static class ThrowingController {
        @GetMapping("/business")
        public void business() {
            throw new BusinessException(403, "forbidden");
        }

        @GetMapping("/invalid-business")
        public void invalidBusiness() {
            throw new BusinessException(200, "invalid-business-secret");
        }

        @GetMapping("/unexpected")
        public void unexpected() {
            throw new IllegalStateException("database-password=secret");
        }
    }

    @FunctionalInterface
    private interface ThrowingRunnable {
        void run();
    }
}
