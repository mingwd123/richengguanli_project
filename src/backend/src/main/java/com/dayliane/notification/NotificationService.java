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
import java.util.TreeSet;

@Service
public class NotificationService {
    private static final List<Integer> DEFAULT_REMINDER_PRESET_MINUTES = List.of(15, 30, 60, 1440);
    private static final int MAX_REMINDER_PRESETS = 8;
    private static final int MAX_REMINDER_OFFSET_MINUTES = 30 * 24 * 60;
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
        Map<String, Object> preferences = new LinkedHashMap<>(rows.isEmpty() ? defaultPreferences() : rows.get(0));
        preferences.put("reminderPresetMinutes", reminderPresetMinutes(userId));
        return preferences;
    }

    @Transactional
    public Map<String, Object> updatePreferences(long userId, Map<String, Object> req) {
        Map<String, Object> current = preferences(userId);
        boolean browserEnabled = bool(req, "browserEnabled", current);
        boolean taskAssignedEnabled = bool(req, "taskAssignedEnabled", current);
        boolean taskStatusEnabled = bool(req, "taskStatusEnabled", current);
        boolean reminderEnabled = bool(req, "reminderEnabled", current);
        List<Integer> reminderPresetMinutes = reminderPresetMinutes(req, current);
        if (count("select count(*) from notification_preference where user_id=?", userId) == 0) {
            jdbc.update("insert into notification_preference (user_id,browser_enabled,task_assigned_enabled,task_status_enabled,reminder_enabled) values (?,?,?,?,?)",
                    userId, browserEnabled, taskAssignedEnabled, taskStatusEnabled, reminderEnabled);
        } else {
            jdbc.update("update notification_preference set browser_enabled=?,task_assigned_enabled=?,task_status_enabled=?,reminder_enabled=?,updated_at=utc_timestamp() where user_id=?",
                    browserEnabled, taskAssignedEnabled, taskStatusEnabled, reminderEnabled, userId);
        }
        jdbc.update("delete from reminder_preset where user_id=?", userId);
        for (int index = 0; index < reminderPresetMinutes.size(); index++) {
            jdbc.update("insert into reminder_preset (user_id,offset_minutes,sort_order) values (?,?,?)",
                    userId, reminderPresetMinutes.get(index), (index + 1) * 10);
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

    private List<Integer> reminderPresetMinutes(long userId) {
        List<Integer> values = jdbc.queryForList(
                "select offset_minutes from reminder_preset where user_id=? order by sort_order,id",
                Integer.class, userId);
        return values.isEmpty() ? DEFAULT_REMINDER_PRESET_MINUTES : values;
    }

    private static List<Integer> reminderPresetMinutes(Map<String, Object> req, Map<String, Object> current) {
        if (!req.containsKey("reminderPresetMinutes")) {
            Object existing = current.get("reminderPresetMinutes");
            if (existing instanceof List<?> list) return list.stream().map(NotificationService::positiveInt).toList();
            return DEFAULT_REMINDER_PRESET_MINUTES;
        }
        Object raw = req.get("reminderPresetMinutes");
        if (!(raw instanceof List<?> list)) throw new BusinessException(400, "reminderPresetMinutes must be a list");
        if (list.isEmpty()) throw new BusinessException(400, "at least one reminder preset is required");
        if (list.size() > MAX_REMINDER_PRESETS) throw new BusinessException(400, "too many reminder presets");
        TreeSet<Integer> values = new TreeSet<>();
        for (Object item : list) {
            int minutes = positiveInt(item);
            if (minutes > MAX_REMINDER_OFFSET_MINUTES) throw new BusinessException(400, "reminder preset is too large");
            if (!values.add(minutes)) throw new BusinessException(400, "reminder presets must be unique");
        }
        return List.copyOf(values);
    }

    private static int positiveInt(Object value) {
        try {
            int parsed = value instanceof Number number ? number.intValue() : Integer.parseInt(String.valueOf(value));
            if (parsed <= 0) throw new NumberFormatException();
            return parsed;
        } catch (NumberFormatException e) {
            throw new BusinessException(400, "reminder preset must be a positive integer");
        }
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
