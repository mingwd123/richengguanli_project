package com.dayliane.user;

import com.dayliane.common.BusinessException;
import com.dayliane.common.PermissionService;
import com.dayliane.common.RefreshTokenStore;
import com.dayliane.fatigue.FatigueService;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Service;

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
    private final BCryptPasswordEncoder passwordEncoder = new BCryptPasswordEncoder();

    public UserService(JdbcTemplate jdbc, PermissionService permissionService, RefreshTokenStore refreshTokenStore, FatigueService fatigueService) {
        this.jdbc = jdbc;
        this.permissionService = permissionService;
        this.refreshTokenStore = refreshTokenStore;
        this.fatigueService = fatigueService;
    }

    public Map<String, Object> userView(long userId) {
        Map<String, Object> user = requireUserEntity(userId);
        user.remove("passwordHash");
        return user;
    }

    public Map<String, Object> requireUserEntity(long userId) {
        try {
            return jdbc.queryForObject("select id, phone, password_hash passwordHash, nickname, avatar_url avatarUrl, timezone, status, created_at createdAt from `user` where id = ? and deleted_at is null", userMapper(), userId);
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

    public void updatePassword(long userId, String oldPassword, String newPassword) {
        Map<String, Object> user = requireUserEntity(userId);
        String hash = String.valueOf(user.get("passwordHash"));
        if (!passwordMatches(oldPassword, hash)) throw new BusinessException(400, "old password is incorrect");
        if (newPassword == null || newPassword.length() < 8 || !newPassword.matches(".*[A-Za-z].*") || !newPassword.matches(".*\\d.*")) throw new BusinessException(400, "new password format is invalid");
        jdbc.update("update `user` set password_hash=?, token_version=token_version+1 where id=?", passwordEncoder.encode(newPassword), userId);
        refreshTokenStore.invalidateAllForUser(userId);
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
            m.put("passwordHash", rs.getString("passwordHash"));
            m.put("nickname", rs.getString("nickname"));
            m.put("avatarUrl", rs.getString("avatarUrl"));
            m.put("timezone", rs.getString("timezone"));
            m.put("status", rs.getString("status"));
            m.put("createdAt", iso(rs.getTimestamp("createdAt")));
            return m;
        };
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

    private static boolean blank(String s) {
        return s == null || s.isBlank();
    }

    private static String iso(Timestamp ts) {
        return ts == null ? "" : OffsetDateTime.ofInstant(ts.toInstant(), ZoneOffset.UTC).toString();
    }

    private static long longValue(Object v) {
        return ((Number) v).longValue();
    }
}
