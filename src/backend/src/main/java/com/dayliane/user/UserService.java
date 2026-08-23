package com.dayliane.user;

import com.dayliane.auth.email.EmailAddress;
import com.dayliane.auth.email.EmailCodePurpose;
import com.dayliane.auth.email.EmailOtpService;
import com.dayliane.auth.email.EmailOtpVerificationException;
import com.dayliane.common.BusinessException;
import com.dayliane.common.CurrentPasswordRateLimiter;
import com.dayliane.common.PermissionService;
import com.dayliane.common.RefreshTokenStore;
import com.dayliane.fatigue.FatigueService;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.ZoneId;
import java.time.DateTimeException;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

@Service
public class UserService {
    private final JdbcTemplate jdbc;
    private final PermissionService permissionService;
    private final RefreshTokenStore refreshTokenStore;
    private final FatigueService fatigueService;
    private final EmailOtpService emailOtpService;
    private final CurrentPasswordRateLimiter currentPasswordRateLimiter;
    private final BCryptPasswordEncoder passwordEncoder = new BCryptPasswordEncoder();

    public UserService(JdbcTemplate jdbc, PermissionService permissionService, RefreshTokenStore refreshTokenStore,
                       FatigueService fatigueService, EmailOtpService emailOtpService,
                       CurrentPasswordRateLimiter currentPasswordRateLimiter) {
        this.jdbc = jdbc;
        this.permissionService = permissionService;
        this.refreshTokenStore = refreshTokenStore;
        this.fatigueService = fatigueService;
        this.emailOtpService = emailOtpService;
        this.currentPasswordRateLimiter = currentPasswordRateLimiter;
    }

    public Map<String, Object> userView(long userId) {
        Map<String, Object> user = requireUserEntity(userId);
        user.remove("passwordHash");
        return user;
    }

    public Map<String, Object> requireUserEntity(long userId) {
        try {
            return jdbc.queryForObject("select id,phone,email,email_verified_at emailVerifiedAt,password_hash passwordHash,"
                    + "nickname,avatar_url avatarUrl,timezone,status,created_at createdAt "
                    + "from `user` where id=? and deleted_at is null", userMapper(), userId);
        } catch (EmptyResultDataAccessException ex) {
            throw new BusinessException(404, "user not found");
        }
    }

    public void updateUserProfile(long userId, Map<String, Object> req) {
        if (req.containsKey("nickname")) {
            String nickname = nullableText(req.get("nickname"));
            if (nickname == null || nickname.length() > 50) throw new BusinessException(400, "nickname is invalid");
            jdbc.update("update `user` set nickname=? where id=? and deleted_at is null", nickname, userId);
        }
        if (req.containsKey("avatarUrl")) {
            String avatarUrl = Objects.toString(req.get("avatarUrl"), "").trim();
            if (avatarUrl.length() > 500 || (!avatarUrl.isBlank() && !avatarUrl.matches("https?://.+"))) {
                throw new BusinessException(400, "avatarUrl must be an http(s) URL");
            }
            jdbc.update("update `user` set avatar_url=? where id=? and deleted_at is null", avatarUrl.isBlank() ? null : avatarUrl, userId);
        }
    }

    @Transactional
    public void updatePassword(long userId, String oldPassword, String newPassword) {
        Map<String, Object> user = requireUserEntityForUpdate(userId);
        currentPasswordRateLimiter.acquireAttempt(userId);
        String hash = String.valueOf(user.get("passwordHash"));
        if (!passwordMatches(oldPassword, hash)) throw new BusinessException(400, "old password is incorrect");
        currentPasswordRateLimiter.clearSuccess(userId);
        if (!validPassword(newPassword)) throw new BusinessException(400, "new password format is invalid");
        jdbc.update("update `user` set password_hash=?, token_version=token_version+1 where id=?", passwordEncoder.encode(newPassword), userId);
        refreshTokenStore.invalidateAllForUser(userId);
    }

    @Transactional(noRollbackFor = EmailOtpVerificationException.class)
    public boolean updateEmail(long userId, String email, String code, String currentPassword) {
        String normalizedEmail = EmailAddress.normalize(email);
        Map<String, Object> user = requireUserEntityForUpdate(userId);
        currentPasswordRateLimiter.acquireAttempt(userId);
        if (!passwordMatches(currentPassword, String.valueOf(user.get("passwordHash")))) {
            throw new BusinessException(400, "current password is incorrect");
        }
        currentPasswordRateLimiter.clearSuccess(userId);

        String currentEmail = nullableText(user.get("email"));
        if (normalizedEmail.equals(currentEmail)) throw new BusinessException(400, "new email must be different");
        if (emailInUse(normalizedEmail, userId)) throw new BusinessException(409, "email already registered");

        boolean changing = currentEmail != null;
        EmailCodePurpose purpose = changing ? EmailCodePurpose.CHANGE_EMAIL : EmailCodePurpose.BIND_EMAIL;
        emailOtpService.consume(normalizedEmail, purpose, code, userId);
        try {
            int updated = changing
                    ? jdbc.update("update `user` set email=?,email_verified_at=utc_timestamp(),token_version=token_version+1 "
                                    + "where id=? and deleted_at is null",
                            normalizedEmail, userId)
                    : jdbc.update("update `user` set email=?,email_verified_at=utc_timestamp() "
                                    + "where id=? and deleted_at is null",
                            normalizedEmail, userId);
            if (updated != 1) throw new BusinessException(404, "user not found");
        } catch (DuplicateKeyException ex) {
            throw new BusinessException(409, "email already registered");
        }
        if (changing) refreshTokenStore.invalidateAllForUser(userId);
        return changing;
    }

    public void updateTimezone(long userId, String timezone) {
        String next = blank(timezone) ? "Asia/Shanghai" : timezone;
        try { ZoneId.of(next); } catch (DateTimeException ex) { throw new BusinessException(400, "timezone is invalid"); }
        String previous = jdbc.queryForObject("select timezone from `user` where id=? and deleted_at is null", String.class, userId);
        jdbc.update("update `user` set timezone = ? where id = ?", next, userId);
        if (!Objects.equals(previous, next)) fatigueService.timezoneChanged(userId, previous, next);
        else fatigueService.recalculateDates(userId, java.util.List.of(java.time.LocalDate.now(java.time.ZoneId.of(next))));
    }

    // Private helpers

    private RowMapper<Map<String, Object>> userMapper() {
        return (rs, i) -> {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", rs.getLong("id"));
            m.put("phone", rs.getString("phone"));
            m.put("email", rs.getString("email"));
            m.put("emailVerifiedAt", isoNullable(rs.getTimestamp("emailVerifiedAt")));
            m.put("passwordHash", rs.getString("passwordHash"));
            m.put("nickname", rs.getString("nickname"));
            m.put("avatarUrl", rs.getString("avatarUrl"));
            m.put("timezone", rs.getString("timezone"));
            m.put("status", rs.getString("status"));
            m.put("createdAt", iso(rs.getTimestamp("createdAt")));
            return m;
        };
    }

    private Map<String, Object> requireUserEntityForUpdate(long userId) {
        try {
            return jdbc.queryForObject("select id,phone,email,email_verified_at emailVerifiedAt,password_hash passwordHash,"
                            + "nickname,avatar_url avatarUrl,timezone,status,created_at createdAt "
                            + "from `user` where id=? and deleted_at is null for update",
                    userMapper(), userId);
        } catch (EmptyResultDataAccessException ex) {
            throw new BusinessException(404, "user not found");
        }
    }

    private boolean emailInUse(String email, long exceptUserId) {
        Integer count = jdbc.queryForObject("select count(*) from `user` where email=? and id<>?",
                Integer.class, email, exceptUserId);
        return count != null && count > 0;
    }

    private static String nullableText(Object v) {
        if (v == null) return null;
        String s = String.valueOf(v);
        return s.isBlank() ? null : s;
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

    private static boolean validPassword(String password) {
        return password != null
                && password.length() >= 8
                && password.getBytes(StandardCharsets.UTF_8).length <= 72
                && password.matches(".*[A-Za-z].*")
                && password.matches(".*\\d.*");
    }

    private static boolean blank(String s) {
        return s == null || s.isBlank();
    }

    private static String iso(Timestamp ts) {
        return ts == null ? "" : OffsetDateTime.ofInstant(ts.toInstant(), ZoneOffset.UTC).toString();
    }

    private static String isoNullable(Timestamp ts) {
        return ts == null ? null : OffsetDateTime.ofInstant(ts.toInstant(), ZoneOffset.UTC).toString();
    }

    private static long longValue(Object v) {
        return ((Number) v).longValue();
    }
}
