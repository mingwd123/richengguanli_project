package com.dayliane.auth;

import com.dayliane.common.BusinessException;
import com.dayliane.common.JwtService;
import com.dayliane.common.LoginRateLimiter;
import com.dayliane.common.PermissionService;
import com.dayliane.common.RefreshTokenStore;
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
import java.sql.Statement;
import java.sql.Timestamp;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.DateTimeException;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

@Service
public class AuthService {

    private final JdbcTemplate jdbc;
    private final NamedParameterJdbcTemplate named;
    private final BCryptPasswordEncoder passwordEncoder = new BCryptPasswordEncoder();
    private final JwtService jwtService;
    private final LoginRateLimiter rateLimiter;
    private final RefreshTokenStore refreshTokenStore;
    private final PermissionService permissionService;

    public AuthService(JdbcTemplate jdbc, NamedParameterJdbcTemplate named,
                       JwtService jwtService, LoginRateLimiter rateLimiter,
                       RefreshTokenStore refreshTokenStore, PermissionService permissionService) {
        this.jdbc = jdbc;
        this.named = named;
        this.jwtService = jwtService;
        this.rateLimiter = rateLimiter;
        this.refreshTokenStore = refreshTokenStore;
        this.permissionService = permissionService;
    }

    @Transactional
    public long register(String phone, String password, String nickname, String timezone) {
        if (phone == null || phone.isBlank() || password == null || password.length() < 8 || !password.matches(".*[A-Za-z].*") || !password.matches(".*\\d.*")) {
            throw new BusinessException(400, "phone or password format is invalid");
        }
        if (count("select count(*) from `user` where phone = ?", phone) > 0) {
            throw new BusinessException(409, "phone already registered");
        }
        String selectedTimezone = blank(timezone) ? "Asia/Shanghai" : timezone;
        try { ZoneId.of(selectedTimezone); } catch (DateTimeException ex) { throw new BusinessException(400, "timezone is invalid"); }
        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbc.update(con -> {
            PreparedStatement ps = con.prepareStatement(
                    "insert into `user` (phone,password_hash,nickname,avatar_url,timezone,status) values (?,?,?,?,?, 'active')",
                    new String[]{"id"});
            ps.setString(1, phone);
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

    public Map<String, Object> login(String phone, String password, String ip) {
        rateLimiter.checkLimit(ip, phone);
        Map<String, Object> user = findUserByPhone(phone);
        if (user == null || !"active".equals(user.get("status"))) {
            rateLimiter.recordFailure(ip, phone);
            throw new BusinessException(401, "phone or password is incorrect");
        }
        String hash = String.valueOf(user.get("passwordHash"));
        if (!passwordMatches(password, hash)) {
            rateLimiter.recordFailure(ip, phone);
            throw new BusinessException(401, "phone or password is incorrect");
        }
        rateLimiter.clearSuccess(ip, phone);
        long userId = longValue(user.get("id"));
        return tokensMap(userId);
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
        if (count("select count(*) from `user` where id=? and status='active' and deleted_at is null", userId) == 0) {
            throw new BusinessException(401, "refresh token is invalid");
        }
        if (jwtService.getTokenVersion(refreshToken) != tokenVersion(userId)) {
            throw new BusinessException(401, "refresh token is invalid");
        }
        String jti = jwtService.getJti(refreshToken);
        if (jti == null || !refreshTokenStore.consume(jti, userId)) {
            throw new BusinessException(401, "refresh token is invalid");
        }
        String newAccessToken = issueAccessToken(userId);
        String newRefreshToken = issueRefreshToken(userId);
        return Map.of(
                "userId", userId,
                "accessToken", newAccessToken,
                "refreshToken", newRefreshToken,
                "expiresIn", 86400
        );
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

    private Map<String, Object> tokensMap(long userId) {
        return Map.of(
                "userId", userId,
                "accessToken", issueAccessToken(userId),
                "refreshToken", issueRefreshToken(userId),
                "expiresIn", 86400
        );
    }

    private Map<String, Object> findUserByPhone(String phone) {
        try {
            return jdbc.queryForObject(
                    "select id,phone,password_hash passwordHash,nickname,avatar_url avatarUrl,timezone,status,created_at createdAt from `user` where phone=? and deleted_at is null",
                    userMapper(), phone);
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

    private static String text(Map<String, Object> m, String k) {
        return String.valueOf(m.getOrDefault(k, ""));
    }

    private static String textOr(Map<String, Object> m, String k, String f) {
        String v = text(m, k);
        return blank(v) ? f : v;
    }

    private static long longValue(Object v) {
        return ((Number) v).longValue();
    }

    private static String iso(Timestamp ts) {
        return ts == null ? "" : OffsetDateTime.ofInstant(ts.toInstant(), ZoneOffset.UTC).toString();
    }
}
