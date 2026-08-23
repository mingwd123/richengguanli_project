package com.dayliane.auth;

import com.dayliane.auth.email.EmailCodePurpose;
import com.dayliane.auth.email.EmailOtpService;
import com.dayliane.auth.email.EmailProperties;
import com.dayliane.auth.email.EmailSender;
import com.dayliane.auth.email.EmailVerificationMessage;
import com.dayliane.common.BusinessException;
import com.dayliane.user.UserService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.http.MediaType;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@ActiveProfiles("test")
@Import(EmailAuthIntegrationTests.EmailTestConfiguration.class)
@AutoConfigureMockMvc
class EmailAuthIntegrationTests {
    @Autowired private AuthService authService;
    @Autowired private UserService userService;
    @Autowired private EmailOtpService emailOtpService;
    @Autowired private EmailProperties emailProperties;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private RecordingEmailSender emailSender;
    @Autowired private MockMvc mvc;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private PlatformTransactionManager transactionManager;
    private final BCryptPasswordEncoder passwordEncoder = new BCryptPasswordEncoder();

    @BeforeEach
    void cleanDatabase() {
        emailProperties.setRegistrationEnabled(true);
        for (String table : List.of("auth_revoked_access_token", "auth_refresh_token", "auth_email_otp",
                "task_group", "user")) {
            jdbc.update("delete from " + ("user".equals(table) ? "`user`" : table));
        }
        emailSender.clear();
    }

    @Test
    void verifiedEmailRegistrationAllowsEmailLoginWithoutPhoneAndCannotReplayCode() {
        int countdown = authService.sendEmailCode(null, "  New.User@Example.COM ", "register", null,
                "203.0.113.10", "email-auth-test");
        String code = emailSender.lastCode();

        assertThat(countdown).isEqualTo(60);
        assertThat(code).matches("\\d{6}");
        Map<String, Object> storedOtp = jdbc.queryForMap(
                "select email,code_digest codeDigest,status from auth_email_otp");
        assertThat(storedOtp)
                .containsEntry("email", "new.user@example.com")
                .containsEntry("status", "issued");
        assertThat(String.valueOf(storedOtp.get("codeDigest")))
                .hasSize(64)
                .doesNotContain(code);

        long userId = authService.register("NEW.USER@example.com", code, "Abc12345", null,
                "Email User", "UTC");
        Map<String, Object> user = userService.userView(userId);
        assertThat(user)
                .containsEntry("email", "new.user@example.com")
                .containsEntry("phone", null);
        assertThat(user.get("emailVerifiedAt")).isNotNull();
        assertThat(authService.login("new.user@example.com", "Abc12345", "203.0.113.11"))
                .containsEntry("userId", userId);
        assertBusinessCode(409, () -> authService.register("new.user@example.com", code, "Abc12345",
                null, "Replay", "UTC"));
    }

    @Test
    void consumedCodeCannotBeUsedTwice() {
        authService.sendEmailCode(null, "once@example.com", "register", null,
                "203.0.113.9", "email-auth-test");
        String code = emailSender.lastCode();

        emailOtpService.consume("once@example.com", EmailCodePurpose.REGISTER, code, null);

        assertBusinessCode(400, () -> emailOtpService.consume(
                "once@example.com", EmailCodePurpose.REGISTER, code, null));
    }

    @Test
    void concurrentConsumersOnlyAllowOneSuccess() throws Exception {
        authService.sendEmailCode(null, "parallel@example.com", "register", null,
                "203.0.113.8", "email-auth-test");
        String code = emailSender.lastCode();
        ExecutorService executor = Executors.newFixedThreadPool(2);
        CountDownLatch start = new CountDownLatch(1);
        AtomicInteger successes = new AtomicInteger();
        try {
            for (int index = 0; index < 2; index++) {
                executor.submit(() -> {
                    try {
                        start.await();
                        emailOtpService.consume("parallel@example.com", EmailCodePurpose.REGISTER, code, null);
                        successes.incrementAndGet();
                    } catch (BusinessException ignored) {
                        // One contender must lose after the code is consumed.
                    } catch (InterruptedException ex) {
                        Thread.currentThread().interrupt();
                    }
                });
            }
            start.countDown();
            executor.shutdown();
            assertThat(executor.awaitTermination(5, TimeUnit.SECONDS)).isTrue();
        } finally {
            executor.shutdownNow();
        }

        assertThat(successes).hasValue(1);
        assertThat(jdbc.queryForObject(
                "select count(*) from auth_email_otp where status='consumed'", Integer.class)).isEqualTo(1);
    }

    @Test
    void concurrentInitialSendRequestsStillIssueOnlyOneCode() throws Exception {
        ExecutorService executor = Executors.newFixedThreadPool(2);
        CountDownLatch start = new CountDownLatch(1);
        AtomicInteger successes = new AtomicInteger();
        AtomicInteger limited = new AtomicInteger();
        try {
            for (int index = 0; index < 2; index++) {
                executor.submit(() -> {
                    try {
                        start.await();
                        authService.sendEmailCode(null, "parallel-send@example.com", "register", null,
                                "203.0.113.7", "email-auth-test");
                        successes.incrementAndGet();
                    } catch (BusinessException ex) {
                        if (ex.getCode() == 429) limited.incrementAndGet();
                    } catch (InterruptedException ex) {
                        Thread.currentThread().interrupt();
                    }
                });
            }
            start.countDown();
            executor.shutdown();
            assertThat(executor.awaitTermination(5, TimeUnit.SECONDS)).isTrue();
        } finally {
            executor.shutdownNow();
        }

        assertThat(successes).hasValue(1);
        assertThat(limited).hasValue(1);
        assertThat(emailSender.messages()).hasSize(1);
        assertThat(jdbc.queryForObject(
                "select count(*) from auth_email_otp where status='issued'", Integer.class)).isEqualTo(1);
    }

    @Test
    void emailCodeEndpointReturnsCountdownButNeverReturnsPlaintextCode() throws Exception {
        String response = mvc.perform(post("/api/v1/auth/email-code")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"api@example.com\",\"purpose\":\"register\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.countdown").value(60))
                .andReturn().getResponse().getContentAsString();

        assertThat(response).doesNotContain(emailSender.lastCode());
    }

    @Test
    void publicRegisterEndpointDoesNotAcceptLegacyPhoneOnlyRegistration() throws Exception {
        mvc.perform(post("/api/v1/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"phone\":\"15100009999\",\"password\":\"Abc12345\"}"))
                .andExpect(status().isBadRequest());
        assertThat(jdbc.queryForObject("select count(*) from `user`", Integer.class)).isZero();
    }

    @Test
    void registrationRollbackSwitchStopsEmailRegistrationButKeepsLegacyAuthPaths() throws Exception {
        emailProperties.setRegistrationEnabled(false);

        assertBusinessCode(503, () -> authService.sendEmailCode(null, "disabled@example.com",
                "register", null, "203.0.113.51", "email-auth-test"));
        mvc.perform(post("/api/v1/auth/email-code")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"disabled-api@example.com\",\"purpose\":\"register\"}"))
                .andExpect(status().isServiceUnavailable());
        mvc.perform(post("/api/v1/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"disabled-register@example.com\",\"code\":\"123456\",\"password\":\"Abc12345\"}"))
                .andExpect(status().isServiceUnavailable());
        assertThat(emailSender.messages()).isEmpty();

        long legacyUserId = authService.register("15100009989", "Abc12345", "Legacy During Rollback", "UTC");
        assertThat(authService.login("15100009989", "Abc12345", "203.0.113.52"))
                .containsEntry("userId", legacyUserId);
    }

    @Test
    void publicRegistrationEndpointReturnsUsableTokens() throws Exception {
        authService.sendEmailCode(null, "endpoint-register@example.com", "register", null,
                "203.0.113.40", "email-auth-test");

        String response = mvc.perform(post("/api/v1/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "email", "endpoint-register@example.com",
                                "code", emailSender.lastCode(),
                                "password", "Abc12345",
                                "nickname", "Endpoint User",
                                "timezone", "UTC"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.userId").isNumber())
                .andExpect(jsonPath("$.data.accessToken").isString())
                .andExpect(jsonPath("$.data.refreshToken").isString())
                .andReturn().getResponse().getContentAsString();

        JsonNode data = objectMapper.readTree(response).path("data");
        long userId = data.path("userId").asLong();
        String accessToken = data.path("accessToken").asText();
        String refreshToken = data.path("refreshToken").asText();
        assertThat(accessToken).isNotBlank();
        assertThat(refreshToken).isNotBlank();
        assertThat(authService.requireUser("Bearer " + accessToken)).isEqualTo(userId);
        assertThat(authService.refreshToken(refreshToken)).containsEntry("userId", userId);
    }

    @Test
    void emailCodeEndpointIgnoresForwardedForByDefault() throws Exception {
        mvc.perform(post("/api/v1/auth/email-code")
                        .with(request -> {
                            request.setRemoteAddr("198.51.100.77");
                            return request;
                        })
                        .header("X-Forwarded-For", "203.0.113.250")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"remote-ip@example.com\",\"purpose\":\"register\"}"))
                .andExpect(status().isOk());

        assertThat(jdbc.queryForObject("select request_ip from auth_email_otp", String.class))
                .isEqualTo("198.51.100.77");
    }

    @Test
    void failedAttemptsPersistAndInvalidateCodeAtConfiguredLimit() {
        authService.sendEmailCode(null, "attempts@example.com", "register", null,
                "203.0.113.12", "email-auth-test");
        String correctCode = emailSender.lastCode();

        for (int attempt = 0; attempt < 5; attempt++) {
            String wrongCode = correctCode.equals("000000") ? "000001" : "000000";
            assertBusinessCode(400, () -> authService.register("attempts@example.com", wrongCode,
                    "Abc12345", null, "Attempts", "UTC"));
        }

        Map<String, Object> otp = jdbc.queryForMap(
                "select attempt_count attemptCount,status from auth_email_otp");
        assertThat(((Number) otp.get("attemptCount")).intValue()).isEqualTo(5);
        assertThat(otp).containsEntry("status", "invalidated");
        assertBusinessCode(400, () -> authService.register("attempts@example.com", correctCode,
                "Abc12345", null, "Attempts", "UTC"));
    }

    @Test
    void expiredCodeIsRejectedAndPersistentlyMarkedExpired() {
        authService.sendEmailCode(null, "expired@example.com", "register", null,
                "203.0.113.31", "email-auth-test");
        String code = emailSender.lastCode();
        jdbc.update("update auth_email_otp set expires_at=?",
                Timestamp.from(Instant.now().minusSeconds(1)));

        assertBusinessCode(400, () -> authService.register("expired@example.com", code,
                "Abc12345", null, "Expired", "UTC"));

        assertThat(jdbc.queryForObject("select status from auth_email_otp", String.class))
                .isEqualTo("expired");
    }

    @Test
    void resendHonorsCooldownAndInvalidatesPreviousCode() {
        authService.sendEmailCode(null, "resend@example.com", "register", null,
                "203.0.113.13", "email-auth-test");
        String firstCode = emailSender.lastCode();
        assertBusinessCode(429, () -> authService.sendEmailCode(null, "resend@example.com", "register",
                null, "203.0.113.13", "email-auth-test"));

        jdbc.update("update auth_email_otp set sent_at=?",
                Timestamp.from(Instant.now().minusSeconds(61)));
        authService.sendEmailCode(null, "resend@example.com", "register", null,
                "203.0.113.13", "email-auth-test");
        String secondCode = emailSender.lastCode();

        assertThat(jdbc.queryForObject(
                "select count(*) from auth_email_otp where status='invalidated'", Integer.class)).isEqualTo(1);
        assertBusinessCode(400, () -> authService.register("resend@example.com", firstCode,
                "Abc12345", null, "Old Code", "UTC"));
        assertThat(authService.register("resend@example.com", secondCode,
                "Abc12345", null, "New Code", "UTC")).isPositive();
    }

    @Test
    void bindingAndChangingEmailRequireCurrentPasswordAndChangeRevokesSessions() {
        long userId = authService.register("15100009991", "Abc12345", "Legacy User", "UTC");
        Map<String, Object> login = authService.login("15100009991", "Abc12345", "203.0.113.14");
        String accessToken = String.valueOf(login.get("accessToken"));
        String refreshToken = String.valueOf(login.get("refreshToken"));
        String authorization = "Bearer " + accessToken;

        assertBusinessCode(400, () -> authService.sendEmailCode(authorization, "bound@example.com",
                "bind_email", "Wrong12345", "203.0.113.14", "email-auth-test"));
        authService.sendEmailCode(authorization, "bound@example.com", "bind_email", "Abc12345",
                "203.0.113.14", "email-auth-test");
        assertThat(userService.updateEmail(userId, "bound@example.com", emailSender.lastCode(), "Abc12345"))
                .isFalse();
        assertThat(authService.requireUser(authorization)).isEqualTo(userId);
        assertThat(authService.login("bound@example.com", "Abc12345", "203.0.113.15"))
                .containsEntry("userId", userId);

        authService.sendEmailCode(authorization, "changed@example.com", "change_email", "Abc12345",
                "203.0.113.14", "email-auth-test");
        assertThat(userService.updateEmail(userId, "changed@example.com", emailSender.lastCode(), "Abc12345"))
                .isTrue();
        assertBusinessCode(401, () -> authService.requireUser(authorization));
        assertBusinessCode(401, () -> authService.refreshToken(refreshToken));
        assertBusinessCode(401, () -> authService.login("bound@example.com", "Abc12345", "203.0.113.16"));
        assertThat(authService.login("changed@example.com", "Abc12345", "203.0.113.17"))
                .containsEntry("userId", userId);
    }

    @Test
    void finalEmailUpdateRejectsWrongPasswordWithoutConsumingCode() {
        long userId = authService.register("15100009993", "Abc12345", "Password Guard", "UTC");
        String accessToken = String.valueOf(authService.login("15100009993", "Abc12345",
                "203.0.113.32").get("accessToken"));
        authService.sendEmailCode("Bearer " + accessToken, "guarded@example.com", "bind_email",
                "Abc12345", "203.0.113.32", "email-auth-test");
        String code = emailSender.lastCode();

        assertBusinessCode(400, () -> userService.updateEmail(userId, "guarded@example.com", code,
                "Wrong12345"));
        assertThat(jdbc.queryForObject("select status from auth_email_otp", String.class))
                .isEqualTo("issued");
        assertThat(userService.updateEmail(userId, "guarded@example.com", code, "Abc12345")).isFalse();
    }

    @Test
    void emailCodeIsBoundToAuthenticatedUserAndTargetAddress() {
        long firstUser = authService.register("15100009994", "Abc12345", "First", "UTC");
        long secondUser = authService.register("15100009995", "Abc12345", "Second", "UTC");
        String firstToken = String.valueOf(authService.login("15100009994", "Abc12345",
                "203.0.113.33").get("accessToken"));
        authService.sendEmailCode("Bearer " + firstToken, "owner@example.com", "bind_email",
                "Abc12345", "203.0.113.33", "email-auth-test");
        String code = emailSender.lastCode();

        assertBusinessCode(400, () -> userService.updateEmail(secondUser, "owner@example.com", code,
                "Abc12345"));
        assertThat(jdbc.queryForObject(
                "select attempt_count from auth_email_otp where user_id=?", Integer.class, firstUser)).isZero();
        assertBusinessCode(400, () -> userService.updateEmail(firstUser, "other@example.com", code,
                "Abc12345"));
        assertThat(userService.updateEmail(firstUser, "owner@example.com", code, "Abc12345")).isFalse();
    }

    @Test
    void sameTargetEmailCodesRemainIsolatedPerAuthenticatedUser() {
        long firstUser = authService.register("15100009981", "Abc12345", "First Scope", "UTC");
        long secondUser = authService.register("15100009982", "Abc12345", "Second Scope", "UTC");
        String firstAuthorization = "Bearer " + authService.login("15100009981", "Abc12345",
                "203.0.113.41").get("accessToken");
        String secondAuthorization = "Bearer " + authService.login("15100009982", "Abc12345",
                "203.0.113.42").get("accessToken");

        authService.sendEmailCode(firstAuthorization, "shared-target@example.com", "bind_email",
                "Abc12345", "203.0.113.41", "email-auth-test");
        String firstCode = emailSender.lastCode();
        authService.sendEmailCode(secondAuthorization, "shared-target@example.com", "bind_email",
                "Abc12345", "203.0.113.42", "email-auth-test");
        String secondCode = emailSender.lastCode();

        assertThat(jdbc.queryForObject(
                "select count(*) from auth_email_otp where status='issued'", Integer.class)).isEqualTo(2);
        assertBusinessCode(400, () -> userService.updateEmail(secondUser, "shared-target@example.com",
                wrongCode(secondCode), "Abc12345"));
        assertThat(jdbc.queryForObject(
                "select attempt_count from auth_email_otp where user_id=?", Integer.class, firstUser)).isZero();
        assertThat(jdbc.queryForObject(
                "select attempt_count from auth_email_otp where user_id=?", Integer.class, secondUser)).isEqualTo(1);
        assertThat(userService.updateEmail(firstUser, "shared-target@example.com", firstCode, "Abc12345"))
                .isFalse();
    }

    @Test
    void currentPasswordFailuresAreRateLimitedAcrossEmailEndpoints() {
        long userId = authService.register("15100009983", "Abc12345", "Step Up", "UTC");
        String authorization = "Bearer " + authService.login("15100009983", "Abc12345",
                "203.0.113.43").get("accessToken");

        for (int attempt = 0; attempt < 2; attempt++) {
            assertBusinessCode(400, () -> authService.sendEmailCode(authorization,
                    "step-up@example.com", "bind_email", "Wrong12345",
                    "203.0.113.43", "email-auth-test"));
        }
        for (int attempt = 0; attempt < 2; attempt++) {
            assertBusinessCode(400, () -> userService.updateEmail(userId, "step-up@example.com",
                    "000000", "Wrong12345"));
        }
        assertBusinessCode(400, () -> userService.updatePassword(
                userId, "Wrong12345", "Changed12345"));

        assertBusinessCode(429, () -> authService.sendEmailCode(authorization,
                "another-step-up@example.com", "bind_email", "Abc12345",
                "203.0.113.43", "email-auth-test"));
        assertThat(emailSender.messages()).isEmpty();
    }

    @Test
    void concurrentCurrentPasswordAttemptsCannotExceedFailureBudget() throws Exception {
        authService.register("15100009987", "Abc12345", "Step Up Parallel", "UTC");
        String authorization = "Bearer " + authService.login("15100009987", "Abc12345",
                "203.0.113.49").get("accessToken");
        int workers = 12;
        ExecutorService executor = Executors.newFixedThreadPool(workers);
        CountDownLatch ready = new CountDownLatch(workers);
        CountDownLatch start = new CountDownLatch(1);
        AtomicInteger passwordFailures = new AtomicInteger();
        AtomicInteger limited = new AtomicInteger();
        List<Future<?>> futures = new ArrayList<>();
        try {
            for (int index = 0; index < workers; index++) {
                int attempt = index;
                futures.add(executor.submit(() -> {
                    ready.countDown();
                    await(start);
                    try {
                        authService.sendEmailCode(authorization,
                                "parallel-step-up-" + attempt + "@example.com", "bind_email",
                                "Wrong12345", "203.0.113.49", "email-auth-test");
                    } catch (BusinessException ex) {
                        if (ex.getCode() == 400) passwordFailures.incrementAndGet();
                        else if (ex.getCode() == 429) limited.incrementAndGet();
                        else throw ex;
                    }
                }));
            }
            assertThat(ready.await(5, TimeUnit.SECONDS)).isTrue();
            start.countDown();
            for (Future<?> future : futures) future.get(5, TimeUnit.SECONDS);
        } finally {
            start.countDown();
            executor.shutdownNow();
            assertThat(executor.awaitTermination(5, TimeUnit.SECONDS)).isTrue();
        }

        assertThat(passwordFailures).hasValue(5);
        assertThat(limited).hasValue(workers - 5);
        assertThat(emailSender.messages()).isEmpty();
    }

    @Test
    void concurrentLoginAttemptsCannotExceedFailureBudget() throws Exception {
        authService.register("15100009988", "Abc12345", "Login Parallel", "UTC");
        int workers = 12;
        ExecutorService executor = Executors.newFixedThreadPool(workers);
        CountDownLatch ready = new CountDownLatch(workers);
        CountDownLatch start = new CountDownLatch(1);
        AtomicInteger credentialFailures = new AtomicInteger();
        AtomicInteger limited = new AtomicInteger();
        List<Future<?>> futures = new ArrayList<>();
        try {
            for (int index = 0; index < workers; index++) {
                futures.add(executor.submit(() -> {
                    ready.countDown();
                    await(start);
                    try {
                        authService.login("15100009988", "Wrong12345", "203.0.113.50");
                    } catch (BusinessException ex) {
                        if (ex.getCode() == 401) credentialFailures.incrementAndGet();
                        else if (ex.getCode() == 429) limited.incrementAndGet();
                        else throw ex;
                    }
                }));
            }
            assertThat(ready.await(5, TimeUnit.SECONDS)).isTrue();
            start.countDown();
            for (Future<?> future : futures) future.get(5, TimeUnit.SECONDS);
        } finally {
            start.countDown();
            executor.shutdownNow();
            assertThat(executor.awaitTermination(5, TimeUnit.SECONDS)).isTrue();
        }

        assertThat(credentialFailures).hasValue(5);
        assertThat(limited).hasValue(workers - 5);
    }

    @Test
    void loginWaitsForConcurrentCredentialUpdateAndRejectsOldPassword() throws Exception {
        long userId = authService.register("15100009984", "Abc12345", "Login Race", "UTC");
        ExecutorService executor = Executors.newFixedThreadPool(2);
        CountDownLatch locked = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        try {
            Future<?> writer = updateCredentialsWhileLocked(executor, userId, "Concurrent12345",
                    false, locked, release);
            assertThat(locked.await(5, TimeUnit.SECONDS)).isTrue();
            CountDownLatch started = new CountDownLatch(1);
            Future<Map<String, Object>> login = executor.submit(() -> {
                started.countDown();
                return authService.login("15100009984", "Abc12345", "203.0.113.44");
            });
            assertThat(started.await(5, TimeUnit.SECONDS)).isTrue();
            assertBlocked(login);

            release.countDown();
            writer.get(5, TimeUnit.SECONDS);
            assertFutureBusinessCode(401, login);
            assertThat(authService.login("15100009984", "Concurrent12345", "203.0.113.45"))
                    .containsEntry("userId", userId);
        } finally {
            release.countDown();
            executor.shutdownNow();
            assertThat(executor.awaitTermination(5, TimeUnit.SECONDS)).isTrue();
        }
    }

    @Test
    void passwordUpdateCannotOverwriteConcurrentCredentialUpdate() throws Exception {
        long userId = authService.register("15100009985", "Abc12345", "Password Race", "UTC");
        ExecutorService executor = Executors.newFixedThreadPool(2);
        CountDownLatch locked = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        try {
            Future<?> writer = updateCredentialsWhileLocked(executor, userId, "Concurrent12345",
                    false, locked, release);
            assertThat(locked.await(5, TimeUnit.SECONDS)).isTrue();
            CountDownLatch started = new CountDownLatch(1);
            Future<?> passwordUpdate = executor.submit(() -> {
                started.countDown();
                userService.updatePassword(userId, "Abc12345", "Requested12345");
            });
            assertThat(started.await(5, TimeUnit.SECONDS)).isTrue();
            assertBlocked(passwordUpdate);

            release.countDown();
            writer.get(5, TimeUnit.SECONDS);
            assertFutureBusinessCode(400, passwordUpdate);
            assertThat(authService.login("15100009985", "Concurrent12345", "203.0.113.46"))
                    .containsEntry("userId", userId);
            assertBusinessCode(401, () -> authService.login(
                    "15100009985", "Requested12345", "203.0.113.47"));
            assertThat(jdbc.queryForObject(
                    "select token_version from `user` where id=?", Integer.class, userId)).isEqualTo(1);
        } finally {
            release.countDown();
            executor.shutdownNow();
            assertThat(executor.awaitTermination(5, TimeUnit.SECONDS)).isTrue();
        }
    }

    @Test
    void refreshWaitsForConcurrentRevocationAndThenRejectsToken() throws Exception {
        long userId = authService.register("15100009986", "Abc12345", "Refresh Race", "UTC");
        String refreshToken = String.valueOf(authService.login("15100009986", "Abc12345",
                "203.0.113.48").get("refreshToken"));
        ExecutorService executor = Executors.newFixedThreadPool(2);
        CountDownLatch locked = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        try {
            Future<?> writer = updateCredentialsWhileLocked(executor, userId, "Concurrent12345",
                    true, locked, release);
            assertThat(locked.await(5, TimeUnit.SECONDS)).isTrue();
            CountDownLatch started = new CountDownLatch(1);
            Future<Map<String, Object>> refresh = executor.submit(() -> {
                started.countDown();
                return authService.refreshToken(refreshToken);
            });
            assertThat(started.await(5, TimeUnit.SECONDS)).isTrue();
            assertBlocked(refresh);

            release.countDown();
            writer.get(5, TimeUnit.SECONDS);
            assertFutureBusinessCode(401, refresh);
            assertThat(jdbc.queryForObject(
                    "select count(*) from auth_refresh_token where user_id=?", Integer.class, userId)).isZero();
        } finally {
            release.countDown();
            executor.shutdownNow();
            assertThat(executor.awaitTermination(5, TimeUnit.SECONDS)).isTrue();
        }
    }

    @Test
    void perEmailDailyLimitAppliesAfterCooldownHasElapsed() {
        for (int send = 0; send < 10; send++) {
            if (send > 0) {
                jdbc.update("update auth_email_otp set sent_at=? where status='issued'",
                        Timestamp.from(Instant.now().minusSeconds(61)));
            }
            authService.sendEmailCode(null, "daily-limit@example.com", "register", null,
                    "203.0.113.34", "email-auth-test");
        }
        jdbc.update("update auth_email_otp set sent_at=? where status='issued'",
                Timestamp.from(Instant.now().minusSeconds(61)));

        assertBusinessCode(429, () -> authService.sendEmailCode(null, "daily-limit@example.com",
                "register", null, "203.0.113.34", "email-auth-test"));
        assertThat(emailSender.messages()).hasSize(10);
    }

    @Test
    void perIpHourlyLimitAppliesAcrossDifferentAddresses() {
        for (int send = 0; send < 30; send++) {
            authService.sendEmailCode(null, "ip-limit-" + send + "@example.com", "register", null,
                    "203.0.113.35", "email-auth-test");
        }

        assertBusinessCode(429, () -> authService.sendEmailCode(null, "ip-limit-blocked@example.com",
                "register", null, "203.0.113.35", "email-auth-test"));
        assertThat(emailSender.messages()).hasSize(30);
    }

    @Test
    void resetPasswordUsesVerifiedEmailAndRevokesPreviousSessions() {
        long userId = authService.register("15100009992", "Abc12345", "Reset User", "UTC");
        jdbc.update("update `user` set email=?,email_verified_at=utc_timestamp() where id=?",
                "reset@example.com", userId);
        String refreshToken = String.valueOf(authService.login("reset@example.com", "Abc12345",
                "203.0.113.18").get("refreshToken"));

        authService.sendEmailCode(null, "reset@example.com", "reset_password", null,
                "203.0.113.18", "email-auth-test");
        authService.resetPassword("reset@example.com", emailSender.lastCode(), "Changed12345");

        assertBusinessCode(401, () -> authService.refreshToken(refreshToken));
        assertBusinessCode(401, () -> authService.login("reset@example.com", "Abc12345", "203.0.113.19"));
        assertThat(authService.login("reset@example.com", "Changed12345", "203.0.113.20"))
                .containsEntry("userId", userId);
    }

    @Test
    void resetCodeRequestDoesNotRevealWhetherEmailExists() {
        int countdown = authService.sendEmailCode(null, "missing@example.com", "reset_password", null,
                "203.0.113.21", "email-auth-test");

        assertThat(countdown).isEqualTo(60);
        assertThat(emailSender.messages()).isEmpty();
        assertThat(jdbc.queryForObject(
                "select count(*) from auth_email_otp where email='missing@example.com' and purpose='reset_password'",
                Integer.class)).isEqualTo(1);
    }

    @Test
    void codeCannotBeUsedForAnotherPurpose() {
        authService.sendEmailCode(null, "purpose@example.com", "register", null,
                "203.0.113.22", "email-auth-test");
        String code = emailSender.lastCode();

        assertBusinessCode(400, () -> authService.resetPassword("purpose@example.com", code,
                "Changed12345"));
        assertThat(authService.register("purpose@example.com", code, "Abc12345", null,
                "Purpose User", "UTC")).isPositive();
    }

    private Future<?> updateCredentialsWhileLocked(ExecutorService executor, long userId, String password,
                                                    boolean revokeRefreshTokens, CountDownLatch locked,
                                                    CountDownLatch release) {
        String passwordHash = passwordEncoder.encode(password);
        return executor.submit(() -> new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
            jdbc.queryForObject("select id from `user` where id=? for update", Long.class, userId);
            locked.countDown();
            await(release);
            jdbc.update("update `user` set password_hash=?,token_version=token_version+1 where id=?",
                    passwordHash, userId);
            if (revokeRefreshTokens) {
                jdbc.update("delete from auth_refresh_token where user_id=?", userId);
            }
        }));
    }

    private static void assertBlocked(Future<?> future) {
        assertThatThrownBy(() -> future.get(200, TimeUnit.MILLISECONDS))
                .isInstanceOf(TimeoutException.class);
    }

    private static void assertFutureBusinessCode(int expectedCode, Future<?> future) throws Exception {
        try {
            future.get(5, TimeUnit.SECONDS);
            throw new AssertionError("expected business exception " + expectedCode);
        } catch (ExecutionException ex) {
            assertThat(ex.getCause()).isInstanceOf(BusinessException.class);
            assertThat(((BusinessException) ex.getCause()).getCode()).isEqualTo(expectedCode);
        }
    }

    private static void await(CountDownLatch latch) {
        try {
            if (!latch.await(5, TimeUnit.SECONDS)) throw new IllegalStateException("latch timed out");
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("latch wait was interrupted", ex);
        }
    }

    private static String wrongCode(String correctCode) {
        return "000000".equals(correctCode) ? "000001" : "000000";
    }

    private static void assertBusinessCode(int expectedCode, ThrowingRunnable runnable) {
        assertThatThrownBy(runnable::run)
                .isInstanceOf(BusinessException.class)
                .extracting(ex -> ((BusinessException) ex).getCode())
                .isEqualTo(expectedCode);
    }

    @FunctionalInterface
    private interface ThrowingRunnable {
        void run();
    }

    @TestConfiguration
    static class EmailTestConfiguration {
        @Bean
        @Primary
        RecordingEmailSender recordingEmailSender() {
            return new RecordingEmailSender();
        }
    }

    static class RecordingEmailSender implements EmailSender {
        private final List<EmailVerificationMessage> messages = new CopyOnWriteArrayList<>();

        @Override
        public void ensureReady() {}

        @Override
        public void send(EmailVerificationMessage message) {
            messages.add(message);
        }

        String lastCode() {
            assertThat(messages).isNotEmpty();
            return messages.get(messages.size() - 1).code();
        }

        List<EmailVerificationMessage> messages() {
            return List.copyOf(messages);
        }

        void clear() {
            messages.clear();
        }
    }
}
