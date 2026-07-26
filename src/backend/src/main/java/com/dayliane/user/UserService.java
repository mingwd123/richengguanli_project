package com.dayliane.user;

import com.dayliane.common.BusinessException;
import com.dayliane.common.PermissionService;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Service;

import java.sql.Timestamp;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

@Service
public class UserService {
    private final JdbcTemplate jdbc;
    private final PermissionService permissionService;
    private final BCryptPasswordEncoder passwordEncoder = new BCryptPasswordEncoder();

    public UserService(JdbcTemplate jdbc, PermissionService permissionService) {
        this.jdbc = jdbc;
        this.permissionService = permissionService;
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
        jdbc.update("update `user` set nickname = coalesce(?, nickname), avatar_url = coalesce(?, avatar_url) where id = ? and deleted_at is null",
                nullableText(req.get("nickname")), nullableText(req.get("avatarUrl")), userId);
    }

    public void updatePassword(long userId, String oldPassword, String newPassword) {
        Map<String, Object> user = requireUserEntity(userId);
        String hash = String.valueOf(user.get("passwordHash"));
        if (!passwordEncoder.matches(oldPassword, hash) && !Objects.equals(oldPassword, hash)) throw new BusinessException(400, "old password is incorrect");
        if (newPassword == null || newPassword.length() < 8 || !newPassword.matches(".*[A-Za-z].*") || !newPassword.matches(".*\\d.*")) throw new BusinessException(400, "new password format is invalid");
        jdbc.update("update `user` set password_hash = ? where id = ?", passwordEncoder.encode(newPassword), userId);
    }

    public void updateTimezone(long userId, String timezone) {
        jdbc.update("update `user` set timezone = ? where id = ?", blank(timezone) ? "Asia/Shanghai" : timezone, userId);
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
