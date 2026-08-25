package com.dayliane.auth;

import com.dayliane.auth.email.EmailAddress;
import com.dayliane.auth.email.EmailCodePurpose;
import com.dayliane.auth.email.EmailOtpService;
import com.dayliane.auth.email.EmailOtpVerificationException;
import com.dayliane.common.BusinessException;
import com.dayliane.common.JwtService;
import com.dayliane.common.LoginRateLimiter;
import com.dayliane.common.PermissionService;
import com.dayliane.common.RefreshTokenStore;
import com.dayliane.common.UserLoginRateLimiter;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.sql.PreparedStatement;
import java.sql.Timestamp;
import java.nio.charset.StandardCharsets;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.ZoneId;
import java.time.DateTimeException;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

@Service
public class AuthService {
    private static final int EMAIL_SEND_LOCK_STRIPES = 256;

    private final JdbcTemplate jdbc;
    private final NamedParameterJdbcTemplate named;
    private final BCryptPasswordEncoder passwordEncoder = new BCryptPasswordEncoder();
    private final JwtService jwtService;
    private final LoginRateLimiter rateLimiter;
    private final UserLoginRateLimiter userLoginRateLimiter;
    private final RefreshTokenStore refreshTokenStore;
    private final PermissionService permissionService;
    private final RegistrationSettingsService registrationSettingsService;
    private final EmailOtpService emailOtpService;
    private final Object[] emailSendLocks = createEmailSendLocks();

    public AuthService(JdbcTemplate jdbc, NamedParameterJdbcTemplate named,
                       JwtService jwtService, LoginRateLimiter rateLimiter,
                       UserLoginRateLimiter userLoginRateLimiter,
                       RefreshTokenStore refreshTokenStore, PermissionService permissionService,
                       RegistrationSettingsService registrationSettingsService,
                       EmailOtpService emailOtpService) {
        this.jdbc = jdbc;
        this.named = named;
        this.jwtService = jwtService;
        this.rateLimiter = rateLimiter;
        this.userLoginRateLimiter = userLoginRateLimiter;
        this.refreshTokenStore = refreshTokenStore;
        this.permissionService = permissionService;
        this.registrationSettingsService = registrationSettingsService;
        this.emailOtpService = emailOtpService;
    }

    @Transactional
    public long register(String phone, String password, String nickname, String timezone) {
        String normalizedPhone = normalizePhone(phone, true);
        if (!validPassword(password)) {
            throw new BusinessException(400, "phone or password format is invalid");
        }
        if (count("select count(*) from `user` where phone = ?", normalizedPhone) > 0) {
            throw new BusinessException(409, "phone already registered");
        }
        String selectedTimezone = validateTimezone(timezone);
        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbc.update(con -> {
            PreparedStatement ps = con.prepareStatement(
                    "insert into `user` (phone,password_hash,nickname,avatar_url,timezone,status) values (?,?,?,?,?, 'active')",
                    new String[]{"id"});
            ps.setString(1, normalizedPhone);
            ps.setString(2, passwordEncoder.encode(password));
            ps.setString(3, blank(nickname) ? "User" : nickname);
            ps.setString(4, "");
            ps.setString(5, selectedTimezone);
            return ps;
        }, keyHolder);
        long userId = Objects.requireNonNull(keyHolder.getKey()).longValue();
        createDefaultTaskGroups(userId);
        return userId;
    }

    @Transactional(noRollbackFor = EmailOtpVerificationException.class)
    public long register(String email, String code, String password, String phone,
                         String nickname, String timezone) {
        registrationSettingsService.requireRegistrationEnabled();
        String normalizedEmail = EmailAddress.normalize(email);
        String normalizedPhone = normalizePhone(phone, false);
        if (!validPassword(password)) throw new BusinessException(400, "password format is invalid");
        String selectedNickname = blank(nickname) ? "User" : nickname.trim();
        if (selectedNickname.length() > 50) throw new BusinessException(400, "nickname is invalid");
        String selectedTimezone = validateTimezone(timezone);
        if (count("select count(*) from `user` where email=?", normalizedEmail) > 0) {
            throw new BusinessException(409, "email already registered");
        }
        if (normalizedPhone != null && count("select count(*) from `user` where phone=?", normalizedPhone) > 0) {
            throw new BusinessException(409, "phone already registered");
        }

        emailOtpService.consume(normalizedEmail, EmailCodePurpose.REGISTER, code, null);
        KeyHolder keyHolder = new GeneratedKeyHolder();
        try {
            jdbc.update(connection -> {
                PreparedStatement statement = connection.prepareStatement(
                        "insert into `user` (phone,email,email_verified_at,password_hash,nickname,avatar_url,timezone,status) "
                                + "values (?,?,utc_timestamp(),?,?,?,?,'active')",
                        new String[]{"id"});
                statement.setString(1, normalizedPhone);
                statement.setString(2, normalizedEmail);
                statement.setString(3, passwordEncoder.encode(password));
                statement.setString(4, selectedNickname);
                statement.setString(5, "");
                statement.setString(6, selectedTimezone);
                return statement;
            }, keyHolder);
        } catch (DuplicateKeyException ex) {
            if (count("select count(*) from `user` where email=?", normalizedEmail) > 0) {
                throw new BusinessException(409, "email already registered");
            }
            throw new BusinessException(409, "phone already registered");
        }
        long userId = Objects.requireNonNull(keyHolder.getKey()).longValue();
        createDefaultTaskGroups(userId);
        return userId;
    }

    @Transactional(noRollbackFor = EmailOtpVerificationException.class)
    public Map<String, Object> registerWithTokens(String email, String code, String password, String phone,
                                                   String nickname, String timezone) {
        long userId = register(email, code, password, phone, nickname, timezone);
        return tokensMap(userId, 0);
    }

    @Transactional
    public Map<String, Object> login(String account, String password, String ip) {
        String normalizedAccount = account == null ? "" : account.trim();
        String normalizedEmail = null;
        if (normalizedAccount.contains("@")) {
            normalizedEmail = EmailAddress.tryNormalize(normalizedAccount);
            normalizedAccount = normalizedEmail == null ? normalizedAccount.toLowerCase() : normalizedEmail;
        }
        userLoginRateLimiter.acquireAttempt(ip, normalizedAccount);
        Map<String, Object> user = normalizedEmail != null
                ? findUserByEmailForUpdate(normalizedEmail)
                : normalizedAccount.contains("@") ? null : findUserByPhoneForUpdate(normalizedAccount);
        if (user == null || !"active".equals(user.get("status"))) {
            throw new BusinessException(401, "account or password is incorrect");
        }
        String hash = String.valueOf(user.get("passwordHash"));
        if (!passwordMatches(password, hash)) {
            throw new BusinessException(401, "account or password is incorrect");
        }
        userLoginRateLimiter.clearSuccess(ip, normalizedAccount);
        long userId = longValue(user.get("id"));
        int version = ((Number) user.get("tokenVersion")).intValue();
        return tokensMap(userId, version);
    }

    public int sendEmailCode(String authorization, String email, String purpose, String currentPassword,
                             String ip, String userAgent) {
        EmailCodePurpose parsedPurpose = EmailCodePurpose.parse(purpose);
        Long userId = parsedPurpose.requiresAuthentication() ? requireUser(authorization) : null;
        String normalizedEmail = EmailAddress.normalize(email);
        Object lock = emailSendLocks[Math.floorMod(
                31 * normalizedEmail.hashCode() + parsedPurpose.value().hashCode(), emailSendLocks.length)];
        synchronized (lock) {
            return emailOtpService.sendCode(parsedPurpose, normalizedEmail, userId, currentPassword, ip, userAgent);
        }
    }

    @Transactional(noRollbackFor = EmailOtpVerificationException.class)
    public void resetPassword(String email, String code, String newPassword) {
        String normalizedEmail = EmailAddress.normalize(email);
        if (!validPassword(newPassword)) throw new BusinessException(400, "new password format is invalid");
        Map<String, Object> user = findResetUserByEmailForUpdate(normalizedEmail);
        Long userId = user == null ? null : longValue(user.get("id"));
        emailOtpService.consume(normalizedEmail, EmailCodePurpose.RESET_PASSWORD, code, userId);
        if (userId == null) throw new EmailOtpVerificationException();
        jdbc.update("update `user` set password_hash=?,token_version=token_version+1 where id=?",
                passwordEncoder.encode(newPassword), userId);
        refreshTokenStore.invalidateAllForUser(userId);
    }

    public Map<String, Object> adminLogin(String username, String password, String ip) {
        rateLimiter.checkLimit(ip, username);
        Map<String, Object> admin = findAdminByUsername(username);
        if (admin == null || !"active".equals(admin.get("status"))) {
            rateLimiter.recordFailure(ip, username);
            throw new BusinessException(401, "username or password is incorrect");
        }
        String hash = String.valueOf(admin.get("passwordHash"));
        if (!passwordMatches(password, hash)) {
            rateLimiter.recordFailure(ip, username);
            throw new BusinessException(401, "username or password is incorrect");
        }
        rateLimiter.clearSuccess(ip, username);
        long adminId = longValue(admin.get("id"));
        jdbc.update("update admin_user set last_login_at=utc_timestamp(), last_login_ip=? where id=?", ip, adminId);
        return adminTokensMap(adminId);
    }

    private Map<String, Object> adminTokensMap(long adminId) {
        return Map.of(
                "adminId", adminId,
                "accessToken", jwtService.issueAdminAccessToken(adminId),
                "expiresIn", 86400
        );
    }

    @Transactional
    public Map<String, Object> refreshToken(String refreshToken) {
        Long userId = jwtService.verifyRefreshToken(refreshToken);
        if (userId == null) {
            throw new BusinessException(401, "refresh token is invalid");
        }
        SessionState sessionState = findSessionStateForUpdate(userId);
        if (sessionState == null || !"active".equals(sessionState.status())
                || jwtService.getTokenVersion(refreshToken) != sessionState.tokenVersion()) {
            throw new BusinessException(401, "refresh token is invalid");
        }
        String jti = jwtService.getJti(refreshToken);
        if (jti == null || !refreshTokenStore.consume(jti, userId)) {
            throw new BusinessException(401, "refresh token is invalid");
        }
        return tokensMap(userId, sessionState.tokenVersion());
    }

    public void logout(String authorization) {
        logout(authorization, null);
    }

    public void logout(String authorization, String refreshToken) {
        if (authorization == null || !authorization.startsWith("Bearer ")) {
            invalidateRefreshToken(refreshToken);
        } else {
            String token = authorization.substring(7);
            String jti = jwtService.getJti(token);
            if (jti != null) {
                refreshTokenStore.invalidateAccessToken(jti);
            }
            invalidateRefreshToken(refreshToken);
        }
    }

    private void invalidateRefreshToken(String refreshToken) {
        if (refreshToken == null || refreshToken.isBlank()) return;
        String refreshJti = jwtService.getJti(refreshToken);
        if (refreshJti != null) {
            refreshTokenStore.invalidate(refreshJti);
        }
    }

    public long requireUser(String authorization) {
        if (authorization == null || !authorization.startsWith("Bearer ")) {
            throw new BusinessException(401, "unauthorized");
        }
        String token = authorization.substring(7);
        String jti = jwtService.getJti(token);
        if (jti != null && refreshTokenStore.isAccessTokenInvalidated(jti)) {
            throw new BusinessException(401, "unauthorized");
        }
        long userId = jwtService.verifyAccessToken(token);
        if (count("select count(*) from `user` where id=? and status='active' and deleted_at is null", userId) == 0) {
            throw new BusinessException(401, "unauthorized");
        }
        if (jwtService.getTokenVersion(token) != tokenVersion(userId)) {
            throw new BusinessException(401, "unauthorized");
        }
        return userId;
    }

    public long requireAdmin(String authorization) {
        if (authorization == null || !authorization.startsWith("Bearer ")) {
            throw new BusinessException(401, "unauthorized");
        }
        long adminId = jwtService.verifyAdminAccessToken(authorization.substring(7));
        if (count("select count(*) from admin_user where id=? and status='active'", adminId) == 0) {
            throw new BusinessException(401, "unauthorized");
        }
        return adminId;
    }

    public String issueAccessToken(long userId) {
        return jwtService.issueAccessToken(userId, tokenVersion(userId));
    }

    public String issueRefreshToken(long userId) {
        String token = jwtService.issueRefreshToken(userId, tokenVersion(userId));
        String jti = jwtService.getJti(token);
        if (jti != null) {
            refreshTokenStore.save(jti, userId);
        }
        return token;
    }

    private Map<String, Object> tokensMap(long userId, int version) {
        String accessToken = jwtService.issueAccessToken(userId, version);
        String refreshToken = jwtService.issueRefreshToken(userId, version);
        String refreshJti = jwtService.getJti(refreshToken);
        if (refreshJti != null) refreshTokenStore.save(refreshJti, userId);
        return Map.of(
                "userId", userId,
                "accessToken", accessToken,
                "refreshToken", refreshToken,
                "expiresIn", 86400
        );
    }

    private Map<String, Object> findUserByPhoneForUpdate(String phone) {
        try {
            return jdbc.queryForObject(
                    "select id,phone,password_hash passwordHash,nickname,avatar_url avatarUrl,timezone,status,"
                            + "token_version tokenVersion,created_at createdAt from `user` "
                            + "where phone=? and deleted_at is null for update",
                    userMapper(), phone);
        } catch (EmptyResultDataAccessException ex) {
            return null;
        }
    }

    private SessionState findSessionStateForUpdate(long userId) {
        try {
            return jdbc.queryForObject(
                    "select status,token_version from `user` where id=? and deleted_at is null for update",
                    (rs, index) -> new SessionState(rs.getString("status"), rs.getInt("token_version")),
                    userId);
        } catch (EmptyResultDataAccessException ex) {
            return null;
        }
    }

    private record SessionState(String status, int tokenVersion) {}

    private Map<String, Object> findUserByEmailForUpdate(String email) {
        try {
            return jdbc.queryForObject(
                    "select id,phone,password_hash passwordHash,nickname,avatar_url avatarUrl,timezone,status,"
                            + "token_version tokenVersion,created_at createdAt from `user` "
                            + "where email=? and email_verified_at is not null and deleted_at is null for update",
                    userMapper(), email);
        } catch (EmptyResultDataAccessException ex) {
            return null;
        }
    }

    private Map<String, Object> findResetUserByEmailForUpdate(String email) {
        try {
            return jdbc.queryForObject(
                    "select id,password_hash passwordHash from `user` where email=? and email_verified_at is not null "
                            + "and status='active' and deleted_at is null for update",
                    (rs, index) -> Map.of(
                            "id", rs.getLong("id"),
                            "passwordHash", rs.getString("passwordHash")),
                    email);
        } catch (EmptyResultDataAccessException ex) {
            return null;
        }
    }

    private Map<String, Object> findAdminByUsername(String username) {
        try {
            return jdbc.queryForObject(
                    "select id,username,password_hash passwordHash,role,status,last_login_at lastLoginAt,last_login_ip lastLoginIp,created_at createdAt from admin_user where username=?",
                    adminMapper(), username);
        } catch (EmptyResultDataAccessException ex) {
            return null;
        }
    }

    private RowMapper<Map<String, Object>> userMapper() {
        return (rs, i) -> {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", rs.getLong("id"));
            m.put("phone", rs.getString("phone"));
            m.put("passwordHash", rs.getString("passwordHash"));
            m.put("nickname", rs.getString("nickname"));
            m.put("avatarUrl", rs.getString("avatarUrl"));
            m.put("timezone", rs.getString("timezone"));
            m.put("status", rs.getString("status"));
            m.put("tokenVersion", rs.getInt("tokenVersion"));
            m.put("createdAt", iso(rs.getTimestamp("createdAt")));
            return m;
        };
    }

    private RowMapper<Map<String, Object>> adminMapper() {
        return (rs, i) -> {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", rs.getLong("id"));
            m.put("username", rs.getString("username"));
            m.put("passwordHash", rs.getString("passwordHash"));
            m.put("role", rs.getString("role"));
            m.put("status", rs.getString("status"));
            m.put("lastLoginAt", iso(rs.getTimestamp("lastLoginAt")));
            m.put("lastLoginIp", rs.getString("lastLoginIp"));
            m.put("createdAt", iso(rs.getTimestamp("createdAt")));
            return m;
        };
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

    private void createDefaultTaskGroups(long userId) {
        String[] names = {"\u5de5\u4f5c", "\u751f\u6d3b", "\u56e2\u961f"};
        for (int i = 0; i < names.length; i++) {
            if (count("select count(*) from task_group where user_id=? and scope='personal' and name=? and deleted_at is null", userId, names[i]) == 0) {
                jdbc.update("insert into task_group (user_id,scope,name,sort_order,is_default) values (?,'personal',?,?,?)",
                        userId, names[i], (i + 1) * 10, i == 0);
            }
        }
    }

    private Integer count(String sql, Object... args) {
        Integer n = jdbc.queryForObject(sql, Integer.class, args);
        return n == null ? 0 : n;
    }

    private int tokenVersion(long userId) {
        Integer version = jdbc.queryForObject("select token_version from `user` where id=? and deleted_at is null", Integer.class, userId);
        if (version == null) throw new BusinessException(401, "unauthorized");
        return version;
    }

    private static boolean blank(String s) {
        return s == null || s.isBlank();
    }

    private static String normalizePhone(String raw, boolean required) {
        String phone = raw == null ? "" : raw.trim();
        if (phone.isEmpty() && !required) return null;
        if (!phone.matches("1[3-9]\\d{9}")) throw new BusinessException(400, "phone format is invalid");
        return phone;
    }

    private static boolean validPassword(String password) {
        return password != null
                && password.length() >= 8
                && password.getBytes(StandardCharsets.UTF_8).length <= 72
                && password.matches(".*[A-Za-z].*")
                && password.matches(".*\\d.*");
    }

    private static String validateTimezone(String timezone) {
        String selected = blank(timezone) ? "Asia/Shanghai" : timezone.trim();
        try {
            ZoneId.of(selected);
        } catch (DateTimeException ex) {
            throw new BusinessException(400, "timezone is invalid");
        }
        return selected;
    }

    private static Object[] createEmailSendLocks() {
        Object[] locks = new Object[EMAIL_SEND_LOCK_STRIPES];
        for (int index = 0; index < locks.length; index++) locks[index] = new Object();
        return locks;
    }

    private static long longValue(Object v) {
        return ((Number) v).longValue();
    }

    private static String iso(Timestamp ts) {
        return ts == null ? "" : OffsetDateTime.ofInstant(ts.toInstant(), ZoneOffset.UTC).toString();
    }
}
