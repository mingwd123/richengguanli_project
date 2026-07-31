package com.dayliane.notification;

import com.dayliane.common.BusinessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

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
        return listNotifications(userId, page, size, isRead, "created_desc");
    }

    public Map<String, Object> listNotifications(long userId, int page, int size, Boolean isRead, String sort) {
        String where = " from notification where user_id=:userId and deleted_at is null";
        MapSqlParameterSource p = new MapSqlParameterSource("userId", userId);
        if (isRead != null) {
            where += " and is_read=:isRead";
            p.addValue("isRead", isRead);
        }
        String order = notificationOrder(sort);
        Integer totalValue = named.queryForObject("select count(*)" + where, p, Integer.class);
        int total = totalValue == null ? 0 : totalValue;
        int safePage = Math.max(1, page);
        int safeSize = Math.min(100, Math.max(1, size));
        p.addValue("limit", safeSize).addValue("offset", (safePage - 1) * safeSize);
        String select = "select id,user_id userId,type,title,content,related_type relatedType,related_id relatedId,reminder_id reminderId,is_read isRead,read_at readAt,created_at createdAt";
        List<Map<String, Object>> rows = named.query(select + where + " order by " + order + " limit :limit offset :offset", p, notificationMapper());
        return Map.of("list", rows, "total", total, "page", safePage, "size", safeSize);
    }

    private static String notificationOrder(String sort) {
        return switch (sort == null ? "created_desc" : sort) {
            case "created_desc" -> "created_at desc, id desc";
            case "created_asc" -> "created_at asc, id asc";
            case "unread_first" -> "is_read asc, created_at desc, id desc";
            default -> throw new BusinessException(400, "sort is invalid");
        };
    }

    public long unreadCount(long userId) {
        Integer n = jdbc.queryForObject("select count(*) from notification where user_id=? and is_read=false and deleted_at is null", Integer.class, userId);
        return n == null ? 0 : n;
    }

    public Map<String, Object> preferences(long userId) {
        List<Map<String, Object>> rows = jdbc.query("select browser_enabled browserEnabled,task_assigned_enabled taskAssignedEnabled,task_status_enabled taskStatusEnabled,reminder_enabled reminderEnabled from notification_preference where user_id=?", (rs, i) -> Map.of(
                "browserEnabled", rs.getBoolean("browserEnabled"),
                "taskAssignedEnabled", rs.getBoolean("taskAssignedEnabled"),
                "taskStatusEnabled", rs.getBoolean("taskStatusEnabled"),
                "reminderEnabled", rs.getBoolean("reminderEnabled")
        ), userId);
        return rows.isEmpty() ? defaultPreferences() : rows.get(0);
    }

    @Transactional
    public Map<String, Object> updatePreferences(long userId, Map<String, Object> req) {
        Map<String, Object> current = preferences(userId);
        boolean browserEnabled = bool(req, "browserEnabled", current);
        boolean taskAssignedEnabled = bool(req, "taskAssignedEnabled", current);
        boolean taskStatusEnabled = bool(req, "taskStatusEnabled", current);
        boolean reminderEnabled = bool(req, "reminderEnabled", current);
        if (count("select count(*) from notification_preference where user_id=?", userId) == 0) {
            jdbc.update("insert into notification_preference (user_id,browser_enabled,task_assigned_enabled,task_status_enabled,reminder_enabled) values (?,?,?,?,?)",
                    userId, browserEnabled, taskAssignedEnabled, taskStatusEnabled, reminderEnabled);
        } else {
            jdbc.update("update notification_preference set browser_enabled=?,task_assigned_enabled=?,task_status_enabled=?,reminder_enabled=?,updated_at=utc_timestamp() where user_id=?",
                    browserEnabled, taskAssignedEnabled, taskStatusEnabled, reminderEnabled, userId);
        }
        return preferences(userId);
    }

    public boolean createNotification(long userId, String type, String title, String content,
                                      String relatedType, long relatedId, Long reminderId) {
        Map<String, Object> preference = preferences(userId);
        boolean enabled = switch (type) {
            case "reminder" -> (boolean) preference.get("reminderEnabled");
            case "task_assigned" -> (boolean) preference.get("taskAssignedEnabled");
            default -> !type.startsWith("task_") || (boolean) preference.get("taskStatusEnabled");
        };
        if (!enabled) return false;
        jdbc.update("insert into notification (user_id,type,title,content,related_type,related_id,reminder_id,is_read) values (?,?,?,?,?,?,?,false)",
                userId, type, title, content, relatedType, relatedId, reminderId);
        return true;
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

    private static String iso(Timestamp ts) {
        return ts == null ? "" : OffsetDateTime.ofInstant(ts.toInstant(), ZoneOffset.UTC).toString();
    }

    private static Map<String, Object> defaultPreferences() {
        return Map.of(
                "browserEnabled", false,
                "taskAssignedEnabled", true,
                "taskStatusEnabled", true,
                "reminderEnabled", true
        );
    }

    private static boolean bool(Map<String, Object> req, String key, Map<String, Object> current) {
        Object value = req.get(key);
        return value == null ? (boolean) current.get(key) : value instanceof Boolean b ? b : Boolean.parseBoolean(String.valueOf(value));
    }

    private int count(String sql, Object... args) {
        Integer value = jdbc.queryForObject(sql, Integer.class, args);
        return value == null ? 0 : value;
    }

    private static boolean blank(String s) {
        return s == null || s.isBlank();
    }
}
