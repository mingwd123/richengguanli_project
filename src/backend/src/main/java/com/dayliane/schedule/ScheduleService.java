package com.dayliane.schedule;

import com.dayliane.common.BusinessException;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.sql.PreparedStatement;
import java.sql.Statement;
import java.sql.Timestamp;
import java.time.*;
import java.util.*;

@Service
public class ScheduleService {
    private final JdbcTemplate jdbc;
    private final NamedParameterJdbcTemplate named;

    public ScheduleService(JdbcTemplate jdbc, NamedParameterJdbcTemplate named) {
        this.jdbc = jdbc;
        this.named = named;
    }

    @Transactional
    public Map<String, Object> createSchedule(long userId, Map<String, Object> req) {
        String title = text(req, "title");
        String timeType = textOr(req, "timeType", "deadline_task");
        if (title.isBlank()) throw new BusinessException(400, "title is required");
        if (!List.of("point_event", "deadline_task", "duration_task").contains(timeType)) throw new BusinessException(400, "timeType is invalid");
        if ("point_event".equals(timeType) && blank(text(req, "startTime"))) throw new BusinessException(400, "startTime is required");
        if ("deadline_task".equals(timeType) && blank(text(req, "deadlineTime"))) throw new BusinessException(400, "deadlineTime is required");
        if ("duration_task".equals(timeType) && (blank(text(req, "startTime")) || blank(text(req, "endTime")))) throw new BusinessException(400, "startTime and endTime are required");
        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbc.update(con -> {
            Map<String, Object> group = resolvePersonalTaskGroup(userId, req);
            PreparedStatement ps = con.prepareStatement("insert into schedule (user_id,title,description,group_id,group_name,time_type,start_time,end_time,deadline_time,status) values (?,?,?,?,?,?,?,?,?, 'pending')", new String[]{"id"});
            ps.setLong(1, userId);
            ps.setString(2, title);
            ps.setString(3, text(req, "description"));
            ps.setLong(4, longValue(group.get("id")));
            ps.setString(5, String.valueOf(group.get("name")));
            ps.setString(6, timeType);
            ps.setTimestamp(7, parseTime(text(req, "startTime")));
            ps.setTimestamp(8, parseTime(text(req, "endTime")));
            ps.setTimestamp(9, parseTime(text(req, "deadlineTime")));
            return ps;
        }, keyHolder);
        long id = Objects.requireNonNull(keyHolder.getKey()).longValue();
        createScheduleReminders(userId, id, req);
        return requireSchedule(id, userId);
    }

    public Map<String, Object> listSchedules(long userId, int page, int size, String status, String groupName) {
        StringBuilder sql = new StringBuilder("select id,user_id userId,title,description,group_id groupId,group_name groupName,time_type timeType,start_time startTime,end_time endTime,deadline_time deadlineTime,status,created_at createdAt from schedule where user_id=:userId and deleted_at is null");
        MapSqlParameterSource p = new MapSqlParameterSource("userId", userId);
        if (!blank(status)) { sql.append(" and status=:status"); p.addValue("status", status); }
        if (!blank(groupName)) { sql.append(" and group_name=:groupName"); p.addValue("groupName", groupName); }
        sql.append(" order by coalesce(deadline_time,start_time,created_at) asc");
        return pageResult(named.query(sql.toString(), p, scheduleMapper()), page, size);
    }

    public Map<String, Object> requireSchedule(long id, long userId) {
        try {
            Map<String, Object> item = jdbc.queryForObject("select id,user_id userId,title,description,group_id groupId,group_name groupName,time_type timeType,start_time startTime,end_time endTime,deadline_time deadlineTime,status,created_at createdAt from schedule where id=? and user_id=? and deleted_at is null", scheduleMapper(), id, userId);
            item.put("hasReminder", count("select count(*) from reminder where target_type='schedule' and target_id=?", id) > 0);
            return item;
        } catch (EmptyResultDataAccessException ex) {
            throw new BusinessException(404, "schedule not found");
        }
    }

    @Transactional
    public Map<String, Object> updateSchedule(long id, long userId, Map<String, Object> req) {
        requireSchedule(id, userId);
        Map<String, Object> group = req.containsKey("groupId") || req.containsKey("groupName") ? resolvePersonalTaskGroup(userId, req) : null;
        jdbc.update("update schedule set title=coalesce(?,title), description=coalesce(?,description), group_id=coalesce(?,group_id), group_name=coalesce(?,group_name), time_type=coalesce(?,time_type), start_time=coalesce(?,start_time), end_time=coalesce(?,end_time), deadline_time=coalesce(?,deadline_time) where id=? and user_id=?",
                nullableText(req.get("title")), nullableText(req.get("description")), group == null ? null : longValue(group.get("id")), group == null ? null : String.valueOf(group.get("name")), nullableText(req.get("timeType")), parseOptional(req.get("startTime")), parseOptional(req.get("endTime")), parseOptional(req.get("deadlineTime")), id, userId);
        return requireSchedule(id, userId);
    }

    public void deleteSchedule(long id, long userId) {
        requireSchedule(id, userId);
        jdbc.update("update schedule set deleted_at=utc_timestamp(), deleted_by=? where id=?", userId, id);
    }

    @Transactional
    public Map<String, Object> setScheduleStatus(long id, long userId, String status) {
        Map<String, Object> schedule = requireSchedule(id, userId);
        String current = String.valueOf(schedule.get("status"));
        if ("completed".equals(current) && "completed".equals(status)) {
            throw new BusinessException(400, "completed schedule cannot be completed again");
        }
        if ("cancelled".equals(current) && "completed".equals(status)) {
            throw new BusinessException(400, "cancelled schedule cannot be completed directly");
        }
        jdbc.update("update schedule set status=? where id=? and user_id=?", status, id, userId);
        if (List.of("completed", "cancelled").contains(status)) cancelPendingReminders("schedule", id, null);
        return Map.of("id", id, "status", status);
    }

    // Task group methods

    public Map<String, Object> listTaskGroups(long userId, String scope) {
        if (!"personal".equals(scope)) throw new BusinessException(400, "scope is invalid");
        createDefaultTaskGroups(userId);
        List<Map<String, Object>> rows = jdbc.query("select id,user_id userId,team_id teamId,scope,name,sort_order sortOrder,is_default isDefault,created_at createdAt from task_group where user_id=? and scope='personal' and deleted_at is null order by sort_order asc, id asc", taskGroupMapper(), userId);
        return Map.of("list", rows, "total", rows.size());
    }

    @Transactional
    public Map<String, Object> createTaskGroup(long userId, Map<String, Object> req) {
        String scope = textOr(req, "scope", "personal");
        String name = text(req, "name").trim();
        if (!"personal".equals(scope)) throw new BusinessException(400, "scope is invalid");
        validateTaskGroupName(name);
        if (count("select count(*) from task_group where user_id=? and scope='personal' and name=? and deleted_at is null", userId, name) > 0) throw new BusinessException(409, "task group already exists");
        Integer nextSort = jdbc.queryForObject("select coalesce(max(sort_order),0) + 10 from task_group where user_id=? and scope='personal' and deleted_at is null", Integer.class, userId);
        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbc.update(con -> {
            PreparedStatement ps = con.prepareStatement("insert into task_group (user_id,scope,name,sort_order,is_default) values (?,'personal',?,?,false)", new String[]{"id"});
            ps.setLong(1, userId);
            ps.setString(2, name);
            ps.setInt(3, nextSort == null ? 10 : nextSort);
            return ps;
        }, keyHolder);
        return requirePersonalTaskGroup(Objects.requireNonNull(keyHolder.getKey()).longValue(), userId);
    }

    public Map<String, Object> updateTaskGroup(long id, long userId, Map<String, Object> req) {
        requirePersonalTaskGroup(id, userId);
        String name = text(req, "name").trim();
        validateTaskGroupName(name);
        if (count("select count(*) from task_group where user_id=? and scope='personal' and name=? and id<>? and deleted_at is null", userId, name, id) > 0) throw new BusinessException(409, "task group already exists");
        jdbc.update("update task_group set name=? where id=? and user_id=? and scope='personal' and deleted_at is null", name, id, userId);
        return requirePersonalTaskGroup(id, userId);
    }

    public void deleteTaskGroup(long id, long userId) {
        requirePersonalTaskGroup(id, userId);
        if (count("select count(*) from task_group where user_id=? and scope='personal' and deleted_at is null", userId) <= 1) throw new BusinessException(400, "at least one task group is required");
        jdbc.update("update task_group set deleted_at=utc_timestamp() where id=? and user_id=? and scope='personal' and deleted_at is null", id, userId);
    }

    // Private helpers

    private RowMapper<Map<String, Object>> scheduleMapper() {
        return (rs, i) -> {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", rs.getLong("id"));
            m.put("userId", rs.getLong("userId"));
            m.put("title", rs.getString("title"));
            m.put("description", rs.getString("description"));
            m.put("groupName", rs.getString("groupName"));
            m.put("timeType", rs.getString("timeType"));
            m.put("startTime", iso(rs.getTimestamp("startTime")));
            m.put("endTime", iso(rs.getTimestamp("endTime")));
            m.put("deadlineTime", iso(rs.getTimestamp("deadlineTime")));
            m.put("status", rs.getString("status"));
            m.put("createdAt", iso(rs.getTimestamp("createdAt")));
            return m;
        };
    }

    private RowMapper<Map<String, Object>> taskGroupMapper() {
        return (rs, i) -> {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", rs.getLong("id"));
            m.put("userId", rs.getObject("userId"));
            m.put("teamId", rs.getObject("teamId"));
            m.put("scope", rs.getString("scope"));
            m.put("name", rs.getString("name"));
            m.put("sortOrder", rs.getInt("sortOrder"));
            m.put("isDefault", rs.getBoolean("isDefault"));
            m.put("createdAt", iso(rs.getTimestamp("createdAt")));
            return m;
        };
    }

    private void createDefaultTaskGroups(long userId) {
        String[] names = {"工作", "生活", "团队"};
        for (int i = 0; i < names.length; i++) {
            if (count("select count(*) from task_group where user_id=? and scope='personal' and name=? and deleted_at is null", userId, names[i]) == 0) {
                jdbc.update("insert into task_group (user_id,scope,name,sort_order,is_default) values (?,'personal',?,?,?)", userId, names[i], (i + 1) * 10, i == 0);
            }
        }
    }

    private Map<String, Object> resolvePersonalTaskGroup(long userId, Map<String, Object> req) {
        Object rawGroupId = req.get("groupId");
        if (rawGroupId != null && !String.valueOf(rawGroupId).isBlank()) return requirePersonalTaskGroup(number(rawGroupId), userId);
        String groupName = text(req, "groupName").trim();
        if (!groupName.isBlank()) {
            try {
                return jdbc.queryForObject("select id,user_id userId,team_id teamId,scope,name,sort_order sortOrder,is_default isDefault,created_at createdAt from task_group where user_id=? and scope='personal' and name=? and deleted_at is null", taskGroupMapper(), userId, groupName);
            } catch (EmptyResultDataAccessException ex) {
                Map<String, Object> created = createTaskGroup(userId, Map.of("scope", "personal", "name", groupName));
                return created;
            }
        }
        createDefaultTaskGroups(userId);
        return jdbc.queryForObject("select id,user_id userId,team_id teamId,scope,name,sort_order sortOrder,is_default isDefault,created_at createdAt from task_group where user_id=? and scope='personal' and deleted_at is null order by sort_order asc, id asc limit 1", taskGroupMapper(), userId);
    }

    private Map<String, Object> requirePersonalTaskGroup(long id, long userId) {
        try {
            return jdbc.queryForObject("select id,user_id userId,team_id teamId,scope,name,sort_order sortOrder,is_default isDefault,created_at createdAt from task_group where id=? and user_id=? and scope='personal' and deleted_at is null", taskGroupMapper(), id, userId);
        } catch (EmptyResultDataAccessException ex) {
            throw new BusinessException(404, "task group not found");
        }
    }

    private void validateTaskGroupName(String name) {
        if (name == null || name.isBlank()) throw new BusinessException(400, "task group name is required");
        if (name.length() > 50) throw new BusinessException(400, "task group name is too long");
    }

    private void createScheduleReminders(long userId, long scheduleId, Map<String, Object> req) {
        Object arr = req.get("remindAts");
        if (arr instanceof List<?> list) {
            for (Object v : list) insertReminder(userId, "schedule", scheduleId, parseTime(String.valueOf(v)));
        } else if (req.get("remindAt") != null) {
            insertReminder(userId, "schedule", scheduleId, parseTime(String.valueOf(req.get("remindAt"))));
        }
    }

    private void insertReminder(long userId, String type, long id, Timestamp remindAt) {
        if (remindAt != null) jdbc.update("insert into reminder (user_id,target_type,target_id,remind_at,status) values (?,?,?,?, 'pending')", userId, type, id, remindAt);
    }

    private void cancelPendingReminders(String type, long id, Long userId) {
        if (userId == null) jdbc.update("update reminder set status='cancelled' where target_type=? and target_id=? and status='pending'", type, id);
        else jdbc.update("update reminder set status='cancelled' where target_type=? and target_id=? and user_id=? and status='pending'", type, id, userId);
    }

    public Map<String, Object> pageResult(List<Map<String, Object>> rows, int page, int size) {
        int p = Math.max(1, page);
        int s = Math.min(100, Math.max(1, size));
        int from = Math.min(rows.size(), (p - 1) * s);
        int to = Math.min(rows.size(), from + s);
        return Map.of("list", rows.subList(from, to), "total", rows.size(), "page", p, "size", s);
    }

    private Integer count(String sql, Object... args) {
        Integer n = jdbc.queryForObject(sql, Integer.class, args);
        return n == null ? 0 : n;
    }

    private static String iso(Timestamp ts) { return ts == null ? "" : OffsetDateTime.ofInstant(ts.toInstant(), ZoneOffset.UTC).toString(); }

    private static boolean blank(String s) { return s == null || s.isBlank(); }

    private static String text(Map<String, Object> m, String k) { return String.valueOf(m.getOrDefault(k, "")); }

    private static String textOr(Map<String, Object> m, String k, String f) { String v = text(m, k); return blank(v) ? f : v; }

    private static String nullableText(Object v) { if (v == null) return null; String s = String.valueOf(v); return s.isBlank() ? null : s; }

    private static long longValue(Object v) { return ((Number) v).longValue(); }

    private static long number(Object v) { return v instanceof Number n ? n.longValue() : Long.parseLong(String.valueOf(v)); }

    private static Timestamp parseOptional(Object v) { return v == null ? null : parseTime(String.valueOf(v)); }

    private static Timestamp parseTime(String value) {
        if (value == null || value.isBlank()) return null;
        try { return Timestamp.from(OffsetDateTime.parse(value).toInstant()); } catch (Exception ignored) {}
        try { return Timestamp.valueOf(LocalDateTime.parse(value)); } catch (Exception ignored) {}
        return null;
    }
}
