package com.dayliane.auth.email;

import com.dayliane.auth.RegistrationSettingsService;
import com.dayliane.common.BusinessException;
import com.dayliane.common.CurrentPasswordRateLimiter;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;

@Service
public class EmailOtpService {
    private static final int MAX_USER_AGENT_LENGTH = 1000;
    private static final Duration RETENTION = Duration.ofDays(7);

    private final JdbcTemplate jdbc;
    private final EmailProperties properties;
    private final EmailSender emailSender;
    private final VerificationEmailRenderer renderer;
    private final CurrentPasswordRateLimiter currentPasswordRateLimiter;
    private final RegistrationSettingsService registrationSettingsService;
    private final BCryptPasswordEncoder passwordEncoder = new BCryptPasswordEncoder();
    private final SecureRandom secureRandom = new SecureRandom();

    public EmailOtpService(JdbcTemplate jdbc, EmailProperties properties, EmailSender emailSender,
                           VerificationEmailRenderer renderer,
                           CurrentPasswordRateLimiter currentPasswordRateLimiter,
                           RegistrationSettingsService registrationSettingsService) {
        this.jdbc = jdbc;
        this.properties = properties;
        this.emailSender = emailSender;
        this.renderer = renderer;
        this.currentPasswordRateLimiter = currentPasswordRateLimiter;
        this.registrationSettingsService = registrationSettingsService;
    }

    @Transactional
    public int sendCode(EmailCodePurpose purpose, String rawEmail, Long authenticatedUserId,
                        String currentPassword, String requestIp, String userAgent) {
        if (purpose == EmailCodePurpose.REGISTER) registrationSettingsService.requireRegistrationEnabled();
        String email = EmailAddress.normalize(rawEmail);
        validateConfiguration();
        emailSender.ensureReady();

        SendTarget target = resolveTarget(purpose, email, authenticatedUserId, currentPassword);
        Instant now = Instant.now();
        String ip = metadata(requestIp, 45, "unknown");
        enforceSendLimits(email, purpose, target.userId(), ip, now);

        invalidateIssuedScope(email, purpose, target.userId(), now);

        String code = "%06d".formatted(secureRandom.nextInt(1_000_000));
        Instant expiresAt = now.plus(properties.getOtpTtl());
        jdbc.update("insert into auth_email_otp "
                        + "(user_id,email,purpose,code_digest,status,attempt_count,max_attempts,sent_at,expires_at,request_ip,user_agent,created_at,updated_at) "
                        + "values (?,?,?,?,'issued',0,?,?,?,?,?,?,?)",
                target.userId(), email, purpose.value(), digest(email, purpose, code),
                properties.getMaxAttempts(), Timestamp.from(now), Timestamp.from(expiresAt), ip,
                metadata(userAgent, MAX_USER_AGENT_LENGTH, null), Timestamp.from(now), Timestamp.from(now));

        if (target.deliver()) {
            long minutes = Math.max(1, (properties.getOtpTtl().toSeconds() + 59) / 60);
            emailSender.send(renderer.render(email, purpose, code, minutes));
        }
        return Math.toIntExact(properties.getResendCooldown().toSeconds());
    }

    @Transactional(noRollbackFor = EmailOtpVerificationException.class)
    public void consume(String rawEmail, EmailCodePurpose purpose, String code, Long expectedUserId) {
        String email = EmailAddress.normalize(rawEmail);
        validateConfiguration();
        Instant now = Instant.now();
        String query = "select id,user_id,code_digest,attempt_count,max_attempts,expires_at "
                + "from auth_email_otp where email=? and purpose=? and status='issued' and "
                + (expectedUserId == null ? "user_id is null " : "user_id=? ")
                + "order by id desc limit 1 for update";
        Object[] args = expectedUserId == null
                ? new Object[]{email, purpose.value()}
                : new Object[]{email, purpose.value(), expectedUserId};
        List<OtpRow> rows = jdbc.query(query, (rs, index) -> new OtpRow(
                rs.getLong("id"),
                rs.getObject("user_id") == null ? null : rs.getLong("user_id"),
                rs.getString("code_digest"),
                rs.getInt("attempt_count"),
                rs.getInt("max_attempts"),
                rs.getTimestamp("expires_at").toInstant()), args);
        if (rows.isEmpty()) throw new EmailOtpVerificationException();

        OtpRow row = rows.get(0);
        if (!row.expiresAt().isAfter(now)) {
            jdbc.update("update auth_email_otp set status='expired', updated_at=? where id=? and status='issued'",
                    Timestamp.from(now), row.id());
            throw new EmailOtpVerificationException();
        }

        boolean codeMatches = code != null && code.matches("\\d{6}") && MessageDigest.isEqual(
                row.codeDigest().getBytes(StandardCharsets.US_ASCII),
                digest(email, purpose, code).getBytes(StandardCharsets.US_ASCII));
        if (!codeMatches) {
            recordFailedAttempt(row, now);
            throw new EmailOtpVerificationException();
        }

        int updated = jdbc.update("update auth_email_otp set status='consumed', consumed_at=?, updated_at=? "
                        + "where id=? and status='issued' and attempt_count<max_attempts",
                Timestamp.from(now), Timestamp.from(now), row.id());
        if (updated != 1) throw new EmailOtpVerificationException();
    }

    @Scheduled(cron = "0 15 4 * * *")
    public void cleanupExpired() {
        Instant now = Instant.now();
        jdbc.update("update auth_email_otp set status='expired', updated_at=? "
                        + "where status='issued' and expires_at<=?",
                Timestamp.from(now), Timestamp.from(now));
        jdbc.update("delete from auth_email_otp where expires_at<?", Timestamp.from(now.minus(RETENTION)));
    }

    private SendTarget resolveTarget(EmailCodePurpose purpose, String email, Long userId, String currentPassword) {
        if (purpose == EmailCodePurpose.REGISTER) {
            if (emailInUse(email, null)) throw new BusinessException(409, "email already registered");
            return new SendTarget(null, true);
        }
        if (purpose == EmailCodePurpose.RESET_PASSWORD) {
            Long resetUserId = activeVerifiedUserId(email);
            return new SendTarget(resetUserId, resetUserId != null);
        }
        if (userId == null) throw new BusinessException(401, "unauthorized");

        AccountState account = requireActiveAccount(userId);
        currentPasswordRateLimiter.acquireAttempt(userId);
        if (!passwordMatches(currentPassword, account.passwordHash())) {
            throw new BusinessException(400, "current password is incorrect");
        }
        currentPasswordRateLimiter.clearSuccess(userId);
        if (purpose == EmailCodePurpose.BIND_EMAIL && account.email() != null) {
            throw new BusinessException(409, "email is already bound");
        }
        if (purpose == EmailCodePurpose.CHANGE_EMAIL && account.email() == null) {
            throw new BusinessException(400, "email is not bound");
        }
        if (email.equals(account.email())) throw new BusinessException(400, "new email must be different");
        if (emailInUse(email, userId)) throw new BusinessException(409, "email already registered");
        return new SendTarget(userId, true);
    }

    private void enforceSendLimits(String email, EmailCodePurpose purpose, Long userId, String ip, Instant now) {
        String cooldownQuery = "select sent_at from auth_email_otp where email=? and purpose=? and "
                + (userId == null ? "user_id is null " : "user_id=? ")
                + "order by sent_at desc,id desc limit 1 for update";
        Object[] cooldownArgs = userId == null
                ? new Object[]{email, purpose.value()}
                : new Object[]{email, purpose.value(), userId};
        List<Timestamp> sent = jdbc.query(cooldownQuery,
                (rs, index) -> rs.getTimestamp("sent_at"), cooldownArgs);
        if (!sent.isEmpty() && sent.get(0).toInstant().plus(properties.getResendCooldown()).isAfter(now)) {
            throw new BusinessException(429, "email code was sent recently");
        }

        Integer emailCount = jdbc.queryForObject(
                "select count(*) from auth_email_otp where email=? and sent_at>=?",
                Integer.class, email, Timestamp.from(now.minus(Duration.ofDays(1))));
        if (emailCount != null && emailCount >= properties.getMaxPerEmailPerDay()) {
            throw new BusinessException(429, "email code send limit exceeded");
        }

        Integer ipCount = jdbc.queryForObject(
                "select count(*) from auth_email_otp where request_ip=? and sent_at>=?",
                Integer.class, ip, Timestamp.from(now.minus(Duration.ofHours(1))));
        if (ipCount != null && ipCount >= properties.getMaxPerIpPerHour()) {
            throw new BusinessException(429, "email code send limit exceeded");
        }
    }

    private void invalidateIssuedScope(String email, EmailCodePurpose purpose, Long userId, Instant now) {
        if (userId == null) {
            jdbc.update("update auth_email_otp set status='invalidated',invalidated_at=?,updated_at=? "
                            + "where email=? and purpose=? and status='issued' and user_id is null",
                    Timestamp.from(now), Timestamp.from(now), email, purpose.value());
        } else {
            jdbc.update("update auth_email_otp set status='invalidated',invalidated_at=?,updated_at=? "
                            + "where email=? and purpose=? and status='issued' and user_id=?",
                    Timestamp.from(now), Timestamp.from(now), email, purpose.value(), userId);
        }
    }

    private void recordFailedAttempt(OtpRow row, Instant now) {
        int nextAttempt = row.attemptCount() + 1;
        if (nextAttempt >= row.maxAttempts()) {
            jdbc.update("update auth_email_otp set attempt_count=?,status='invalidated',invalidated_at=?,updated_at=? "
                            + "where id=? and status='issued'",
                    nextAttempt, Timestamp.from(now), Timestamp.from(now), row.id());
        } else {
            jdbc.update("update auth_email_otp set attempt_count=?,updated_at=? where id=? and status='issued'",
                    nextAttempt, Timestamp.from(now), row.id());
        }
    }

    private AccountState requireActiveAccount(long userId) {
        try {
            return jdbc.queryForObject(
                    "select email,password_hash from `user` where id=? and status='active' and deleted_at is null",
                    (rs, index) -> new AccountState(rs.getString("email"), rs.getString("password_hash")), userId);
        } catch (EmptyResultDataAccessException ex) {
            throw new BusinessException(401, "unauthorized");
        }
    }

    private Long activeVerifiedUserId(String email) {
        List<Long> rows = jdbc.query(
                "select id from `user` where email=? and email_verified_at is not null "
                        + "and status='active' and deleted_at is null",
                (rs, index) -> rs.getLong("id"), email);
        return rows.isEmpty() ? null : rows.get(0);
    }

    private boolean emailInUse(String email, Long exceptUserId) {
        Integer count = exceptUserId == null
                ? jdbc.queryForObject("select count(*) from `user` where email=?", Integer.class, email)
                : jdbc.queryForObject("select count(*) from `user` where email=? and id<>?", Integer.class, email, exceptUserId);
        return count != null && count > 0;
    }

    private String digest(String email, EmailCodePurpose purpose, String code) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(properties.getOtpSecret().getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            byte[] value = mac.doFinal(("v1\0" + purpose.value() + "\0" + email + "\0" + code)
                    .getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(value);
        } catch (GeneralSecurityException ex) {
            throw new IllegalStateException("email code digest is unavailable", ex);
        }
    }

    private void validateConfiguration() {
        if (properties.getOtpSecret() == null || properties.getOtpSecret().length() < 16
                || properties.getOtpTtl() == null || properties.getOtpTtl().isNegative() || properties.getOtpTtl().isZero()
                || properties.getResendCooldown() == null || properties.getResendCooldown().isNegative()
                || properties.getMaxAttempts() < 1 || properties.getMaxAttempts() > 127
                || properties.getMaxPerEmailPerDay() < 1 || properties.getMaxPerIpPerHour() < 1) {
            throw new BusinessException(503, "email verification service is unavailable");
        }
    }

    private boolean passwordMatches(String password, String stored) {
        if (password == null || !isBcryptHash(stored)) return false;
        try {
            return passwordEncoder.matches(password, stored);
        } catch (IllegalArgumentException ignored) {
            return false;
        }
    }

    private static boolean isBcryptHash(String value) {
        return value != null && (value.startsWith("$2a$") || value.startsWith("$2b$") || value.startsWith("$2y$"));
    }

    private static String metadata(String value, int maxLength, String fallback) {
        if (value == null || value.isBlank()) return fallback;
        String trimmed = value.trim();
        return trimmed.length() <= maxLength ? trimmed : trimmed.substring(0, maxLength);
    }

    private record SendTarget(Long userId, boolean deliver) {}
    private record AccountState(String email, String passwordHash) {}
    private record OtpRow(long id, Long userId, String codeDigest, int attemptCount, int maxAttempts,
                          Instant expiresAt) {}
}
