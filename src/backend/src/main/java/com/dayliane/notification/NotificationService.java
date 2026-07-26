package com.dayliane.notification;

import com.dayliane.common.BusinessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;

import java.sql.Timestamp;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Service
public class NotificationService {
    private final JdbcTemplate jdbc;
    private final NamedParameterJdbcTemplate named;

    public NotificationService(JdbcTemplate jdbc, NamedParameterJdbcTemplate named) {
        this.jdbc = jdbc;
        this.named = named;
    }

    public Map<String, Object> listNotifications(long userId, int page, int size, Boolean isRead) {
        String sql = "select id,user_id userId,type,title,content,related_type relatedType,related_id relatedId,reminder_id reminderId,is_read isRead,read_at readAt,created_at createdAt from notification where user_id=:userId and deleted_at is null";
        MapSqlParameterSource p = new MapSqlParameterSource("userId", userId);
        if (isRead != null) {
            sql += " and is_read=:isRead";
            p.addValue("isRead", isRead);
        }
        sql += " order by created_at desc";
        List<Map<String, Object>> rows = named.query(sql, p, notificationMapper());
        return pageResult(rows, page, size);
    }

    public long unreadCount(long userId) {
        Integer n = jdbc.queryForObject("select count(*) from notification where user_id=? and is_read=false and deleted_at is null", Integer.class, userId);
        return n == null ? 0 : n;
    }

    public void readNotification(long id, long userId) {
        int updated = jdbc.update("update notification set is_read=true, read_at=utc_timestamp() where id=? and user_id=? and deleted_at is null", id, userId);
        if (updated == 0) throw new BusinessException(404, "notification not found");
    }

    public void readAll(long userId) {
        jdbc.update("update notification set is_read=true, read_at=utc_timestamp() where user_id=? and deleted_at is null", userId);
    }

    public RowMapper<Map<String, Object>> notificationMapper() {
        return (rs, i) -> {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", rs.getLong("id"));
            m.put("userId", rs.getLong("userId"));
            m.put("type", rs.getString("type"));
            m.put("title", rs.getString("title"));
            m.put("content", rs.getString("content"));
            m.put("relatedType", rs.getString("relatedType"));
            m.put("relatedId", rs.getObject("relatedId"));
            m.put("reminderId", rs.getObject("reminderId"));
            m.put("isRead", rs.getBoolean("isRead"));
            m.put("readAt", iso(rs.getTimestamp("readAt")));
            m.put("createdAt", iso(rs.getTimestamp("createdAt")));
            return m;
        };
    }

    public Map<String, Object> pageResult(List<Map<String, Object>> rows, int page, int size) {
        int p = Math.max(1, page);
        int s = Math.min(100, Math.max(1, size));
        int from = Math.min(rows.size(), (p - 1) * s);
        int to = Math.min(rows.size(), from + s);
        return Map.of("list", rows.subList(from, to), "total", rows.size(), "page", p, "size", s);
    }

    private static String iso(Timestamp ts) {
        return ts == null ? "" : OffsetDateTime.ofInstant(ts.toInstant(), ZoneOffset.UTC).toString();
    }

    private static boolean blank(String s) {
        return s == null || s.isBlank();
    }
}
