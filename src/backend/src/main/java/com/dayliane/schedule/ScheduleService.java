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
        Timestamp startTime = parseEditableTime(req.get("startTime"), "startTime");
        Timestamp endTime = parseEditableTime(req.get("endTime"), "endTime");
        Timestamp deadlineTime = parseEditableTime(req.get("deadlineTime"), "deadlineTime");
        if ("point_event".equals(timeType)) { endTime = null; deadlineTime = null; }
        if ("deadline_task".equals(timeType)) { startTime = null; endTime = null; }
        if ("duration_task".equals(timeType)) deadlineTime = null;
        validateScheduleTimes(timeType, startTime, endTime, deadlineTime);
        Timestamp storedStartTime = startTime;
        Timestamp storedEndTime = endTime;
        Timestamp storedDeadlineTime = deadlineTime;
        Map<String, Object> group = resolvePersonalTaskGroup(userId, req);
        int sortOrder = nextScheduleSortOrder(userId, longValue(group.get("id")));
        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbc.update(con -> {
            PreparedStatement ps = con.prepareStatement("insert into schedule (user_id,title,description,group_id,group_name,sort_order,time_type,start_time,end_time,deadline_time,status) values (?,?,?,?,?,?,?,?,?,?, 'pending')", new String[]{"id"});
            ps.setLong(1, userId);
            ps.setString(2, title);
            ps.setString(3, text(req, "description"));
            ps.setLong(4, longValue(group.get("id")));
            ps.setString(5, String.valueOf(group.get("name")));
            ps.setInt(6, sortOrder);
            ps.setString(7, timeType);
            ps.setTimestamp(8, storedStartTime);
            ps.setTimestamp(9, storedEndTime);
            ps.setTimestamp(10, storedDeadlineTime);
            return ps;
        }, keyHolder);
        long id = Objects.requireNonNull(keyHolder.getKey()).longValue();
        createScheduleReminders(userId, id, req);
        return requireSchedule(id, userId);
    }

    public Map<String, Object> listSchedules(long userId, int page, int size, String status, String groupName, String keyword, String dateFrom, String dateTo) {
        return listSchedules(userId, page, size, status, groupName, keyword, dateFrom, dateTo, "manual");
    }

    public Map<String, Object> listSchedules(long userId, int page, int size, String status, String groupName, String keyword, String dateFrom, String dateTo, String sort) {
        StringBuilder where = new StringBuilder(" from schedule where user_id=:userId and deleted_at is null");
        MapSqlParameterSource p = new MapSqlParameterSource("userId", userId);
        if (!blank(status)) { where.append(" and status=:status"); p.addValue("status", status); }
        if (!blank(groupName)) { where.append(" and group_name=:groupName"); p.addValue("groupName", groupName); }
        if (!blank(keyword)) { where.append(" and (title like :keyword or description like :keyword2)"); String kw = "%" + keyword + "%"; p.addValue("keyword", kw); p.addValue("keyword2", kw); }
        if (!blank(dateFrom)) { where.append(" and coalesce(end_time,deadline_time,start_time,created_at) >= :dateFrom"); p.addValue("dateFrom", dateFrom + " 00:00:00"); }
        if (!blank(dateTo)) { where.append(" and coalesce(start_time,deadline_time,end_time,created_at) <= :dateTo"); p.addValue("dateTo", dateTo + " 23:59:59"); }
        String order = "completed".equals(status) && "manual".equals(sort) ? "sort_order asc,id asc" : scheduleOrder(sort);
        int safePage = Math.max(1, page);
        int safeSize = Math.min(100, Math.max(1, size));
        Integer totalValue = named.queryForObject("select count(*)" + where, p, Integer.class);
        int total = totalValue == null ? 0 : totalValue;
        p.addValue("limit", safeSize).addValue("offset", (safePage - 1) * safeSize);
        String select = "select id,user_id userId,title,description,group_id groupId,group_name groupName,sort_order sortOrder,time_type timeType,start_time startTime,end_time endTime,deadline_time deadlineTime,status,created_at createdAt";
        List<Map<String, Object>> rows = named.query(select + where + " order by " + order + " limit :limit offset :offset", p, scheduleMapper());
        addReminderSummaries(rows, userId);
        return pagedResult(rows, total, safePage, safeSize);
    }

    public List<Map<String, Object>> listSchedulesInRange(long userId, String status, Instant startInclusive, Instant endExclusive) {
        String sql = "select id,user_id userId,title,description,group_id groupId,group_name groupName,sort_order sortOrder,time_type timeType,start_time startTime,end_time endTime,deadline_time deadlineTime,status,created_at createdAt " +
                "from schedule where user_id=? and deleted_at is null and (? is null or status=?) " +
                "and coalesce(end_time,deadline_time,start_time,created_at)>=? and coalesce(start_time,deadline_time,end_time,created_at)<? " +
                "order by coalesce(deadline_time,end_time,start_time,created_at),id";
        List<Map<String, Object>> rows = jdbc.query(sql, scheduleMapper(), userId, status, status, Timestamp.from(startInclusive), Timestamp.from(endExclusive));
        addReminderSummaries(rows, userId);
        return rows;
    }

    public Map<String, Object> calendar(long userId, int year, int month) {
        YearMonth yearMonth;
        try {
            yearMonth = YearMonth.of(year, month);
        } catch (DateTimeException ex) {
            throw new BusinessException(400, "year or month is invalid");
        }
        ZoneId zone = userZone(userId);
        Instant monthStart = yearMonth.atDay(1).atStartOfDay(zone).toInstant();
        Instant nextMonthStart = yearMonth.plusMonths(1).atDay(1).atStartOfDay(zone).toInstant();
        List<Map<String, Object>> all = listSchedulesInRange(userId, null, monthStart, nextMonthStart);
        List<Map<String, Object>> days = new ArrayList<>();
        for (int day = 1; day <= yearMonth.lengthOfMonth(); day++) {
            LocalDate date = yearMonth.atDay(day);
            List<Map<String, Object>> schedules = all.stream().filter(item -> overlapsDay(item, date, zone)).toList();
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("date", date.toString());
            row.put("hasSchedule", !schedules.isEmpty());
            row.put("pendingCount", schedules.stream().filter(item -> "pending".equals(item.get("status"))).count());
            row.put("completedCount", schedules.stream().filter(item -> "completed".equals(item.get("status"))).count());
            row.put("schedules", schedules);
            days.add(row);
        }
        return Map.of("year", year, "month", month, "timezone", zone.getId(), "days", days);
    }

    private ZoneId userZone(long userId) {
        String timezone = jdbc.queryForObject("select timezone from `user` where id=? and deleted_at is null", String.class, userId);
        try {
            return ZoneId.of(timezone == null ? "Asia/Shanghai" : timezone);
        } catch (DateTimeException ex) {
            return ZoneId.of("Asia/Shanghai");
        }
    }

    private static boolean overlapsDay(Map<String, Object> schedule, LocalDate date, ZoneId zone) {
        Instant dayStart = date.atStartOfDay(zone).toInstant();
        Instant dayEnd = date.plusDays(1).atStartOfDay(zone).toInstant();
        Instant start = instantValue(schedule.get("startTime"));
        Instant end = instantValue(schedule.get("endTime"));
        if ("duration_task".equals(schedule.get("timeType")) && start != null && end != null) {
            return start.isBefore(dayEnd) && end.isAfter(dayStart);
        }
        Instant point = instantValue(schedule.get("deadlineTime"));
        if (point == null) point = start;
        if (point == null) point = instantValue(schedule.get("createdAt"));
        return point != null && !point.isBefore(dayStart) && point.isBefore(dayEnd);
    }

    private static Instant instantValue(Object value) {
        if (value == null || String.valueOf(value).isBlank()) return null;
        try {
            return OffsetDateTime.parse(String.valueOf(value)).toInstant();
        } catch (DateTimeException ex) {
            return null;
        }
    }

    private static String scheduleOrder(String sort) {
        return switch (sort == null ? "manual" : sort) {
            case "manual" -> "group_id asc, sort_order asc, coalesce(deadline_time,end_time,start_time,created_at) asc, id asc";
            case "time_asc" -> "coalesce(deadline_time,end_time,start_time,created_at) asc, id asc";
            case "time_desc" -> "coalesce(deadline_time,end_time,start_time,created_at) desc, id desc";
            case "created_desc" -> "created_at desc, id desc";
            case "title_asc" -> "title asc, id asc";
            default -> throw new BusinessException(400, "sort is invalid");
        };
    }

    public Map<String, Object> requireSchedule(long id, long userId) {
        try {
            Map<String, Object> item = jdbc.queryForObject("select id,user_id userId,title,description,group_id groupId,group_name groupName,sort_order sortOrder,time_type timeType,start_time startTime,end_time endTime,deadline_time deadlineTime,status,created_at createdAt from schedule where id=? and user_id=? and deleted_at is null", scheduleMapper(), id, userId);
            int reminderCount = count("select count(*) from reminder where target_type='schedule' and target_id=? and status='pending'", id);
            item.put("hasReminder", reminderCount > 0);
            item.put("reminderCount", reminderCount);
            item.put("pendingReminders", jdbc.query("select id,remind_at remindAt,status from reminder where user_id=? and target_type='schedule' and target_id=? and status='pending' order by remind_at", (rs, i) -> Map.of(
                    "id", rs.getLong("id"), "remindAt", iso(rs.getTimestamp("remindAt")), "status", rs.getString("status")), userId, id));
            addReminderSummary(item, userId);
            return item;
        } catch (EmptyResultDataAccessException ex) {
            throw new BusinessException(404, "schedule not found");
        }
    }

    @Transactional
    public Map<String, Object> updateSchedule(long id, long userId, Map<String, Object> req) {
        Map<String, Object> current = requireSchedule(id, userId);
        String title = req.containsKey("title") ? text(req, "title").trim() : String.valueOf(current.get("title"));
        String description = req.containsKey("description") ? text(req, "description") : Objects.toString(current.get("description"), "");
        String timeType = req.containsKey("timeType") ? text(req, "timeType") : String.valueOf(current.get("timeType"));
        if (title.isBlank()) throw new BusinessException(400, "title is required");
        if (!List.of("point_event", "deadline_task", "duration_task").contains(timeType)) throw new BusinessException(400, "timeType is invalid");
        Timestamp currentStartTime = parseTime(String.valueOf(current.getOrDefault("startTime", "")));
        Timestamp currentEndTime = parseTime(String.valueOf(current.getOrDefault("endTime", "")));
        Timestamp currentDeadlineTime = parseTime(String.valueOf(current.getOrDefault("deadlineTime", "")));
        Timestamp startTime = req.containsKey("startTime") ? parseEditableTime(req.get("startTime"), "startTime") : currentStartTime;
        Timestamp endTime = req.containsKey("endTime") ? parseEditableTime(req.get("endTime"), "endTime") : currentEndTime;
        Timestamp deadlineTime = req.containsKey("deadlineTime") ? parseEditableTime(req.get("deadlineTime"), "deadlineTime") : currentDeadlineTime;
        if ("point_event".equals(timeType)) { endTime = null; deadlineTime = null; }
        if ("deadline_task".equals(timeType)) { startTime = null; endTime = null; }
        if ("duration_task".equals(timeType)) deadlineTime = null;
        validateScheduleTimes(timeType, startTime, endTime, deadlineTime);
        Map<String, Object> group = req.containsKey("groupId") || req.containsKey("groupName") ? resolvePersonalTaskGroup(userId, req) : null;
        Integer sortOrder = group == null ? null : nextScheduleSortOrder(userId, longValue(group.get("id")));
        jdbc.update("update schedule set title=?, description=?, group_id=coalesce(?,group_id), group_name=coalesce(?,group_name), sort_order=coalesce(?,sort_order), time_type=?, start_time=?, end_time=?, deadline_time=? where id=? and user_id=?",
                title, description, group == null ? null : longValue(group.get("id")), group == null ? null : String.valueOf(group.get("name")), sortOrder, timeType, startTime, endTime, deadlineTime, id, userId);
        if (req.containsKey("remindAt") || req.containsKey("remindAts")) {
            cancelPendingReminders("schedule", id, userId);
            createScheduleReminders(userId, id, req);
        }
        return requireSchedule(id, userId);
    }

    @Transactional
    public void deleteSchedule(long id, long userId) {
        requireSchedule(id, userId);
        jdbc.update("update schedule set deleted_at=utc_timestamp(), deleted_by=? where id=?", userId, id);
        cancelPendingReminders("schedule", id, userId);
    }

    @Transactional
    public Map<String, Object> setScheduleStatus(long id, long userId, String status) {
        Map<String, Object> schedule = requireSchedule(id, userId);
        String current = String.valueOf(schedule.get("status"));
        if ("completed".equals(current) && "completed".equals(status)) throw new BusinessException(400, "completed schedule cannot be completed again");
        if ("cancelled".equals(current) && "completed".equals(status)) throw new BusinessException(400, "cancelled schedule cannot be completed directly");
        jdbc.update("update schedule set status=? where id=? and user_id=?", status, id, userId);
        if (List.of("completed", "cancelled").contains(status)) cancelPendingReminders("schedule", id, null);
        return Map.of("id", id, "status", status);
    }

    @Transactional
    public Map<String, Object> sortTaskGroups(long userId, Map<String, Object> req) {
        createDefaultTaskGroups(userId);
        List<Long> ids = idList(req, "groupIds");
        validateCompleteIds(ids, jdbc.queryForList("select id from task_group where user_id=? and scope='personal' and deleted_at is null order by sort_order,id", Long.class, userId), "groupIds");
        for (int i = 0; i < ids.size(); i++) jdbc.update("update task_group set sort_order=? where id=? and user_id=? and scope='personal' and deleted_at is null", (i + 1) * 10, ids.get(i), userId);
        return listTaskGroups(userId, "personal");
    }

    @Transactional
    public Map<String, Object> sortSchedules(long userId, Map<String, Object> req) {
        long groupId = requiredId(req, "groupId");
        requirePersonalTaskGroup(groupId, userId);
        List<Long> ids = idList(req, "scheduleIds");
        List<Long> all = jdbc.queryForList("select id from schedule where user_id=? and group_id=? and deleted_at is null order by sort_order,id", Long.class, userId, groupId);
        reorderSubset(all, ids, "scheduleIds");
        for (int i = 0; i < all.size(); i++) jdbc.update("update schedule set sort_order=? where id=? and user_id=? and group_id=? and deleted_at is null", (i + 1) * 10, all.get(i), userId, groupId);
        return Map.of("groupId", groupId, "scheduleIds", all);
    }

    @Transactional
    public Map<String, Object> sortCompletedSchedules(long userId, Map<String, Object> req) {
        List<Long> ids = idList(req, "scheduleIds");
        List<Long> all = jdbc.queryForList("select id from schedule where user_id=? and status='completed' and deleted_at is null order by sort_order,id", Long.class, userId);
        reorderSubset(all, ids, "scheduleIds");
        for (int i = 0; i < all.size(); i++) {
            jdbc.update("update schedule set sort_order=? where id=? and user_id=? and status='completed' and deleted_at is null",
                    (i + 1) * 10, all.get(i), userId);
        }
        return Map.of("scheduleIds", all);
    }

    @Transactional
    public Map<String, Object> moveScheduleGroup(long id, long userId, Map<String, Object> req) {
        requireSchedule(id, userId);
        long groupId = requiredId(req, "groupId");
        Map<String, Object> group = requirePersonalTaskGroup(groupId, userId);
        jdbc.update("update schedule set group_id=?, group_name=?, sort_order=? where id=? and user_id=? and deleted_at is null", groupId, group.get("name"), nextScheduleSortOrder(userId, groupId), id, userId);
        return requireSchedule(id, userId);
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
            ps.setLong(1, userId); ps.setString(2, name); ps.setInt(3, nextSort == null ? 10 : nextSort); return ps;
        }, keyHolder);
        return requirePersonalTaskGroup(Objects.requireNonNull(keyHolder.getKey()).longValue(), userId);
    }

    @Transactional
    public Map<String, Object> updateTaskGroup(long id, long userId, Map<String, Object> req) {
        requirePersonalTaskGroup(id, userId);
        String name = text(req, "name").trim();
        validateTaskGroupName(name);
        if (count("select count(*) from task_group where user_id=? and scope='personal' and name=? and id<>? and deleted_at is null", userId, name, id) > 0) throw new BusinessException(409, "task group already exists");
        jdbc.update("update task_group set name=? where id=? and user_id=? and scope='personal' and deleted_at is null", name, id, userId);
        jdbc.update("update schedule set group_name=? where user_id=? and group_id=? and deleted_at is null", name, userId, id);
        return requirePersonalTaskGroup(id, userId);
    }

    @Transactional
    public void deleteTaskGroup(long id, long userId, Map<String, Object> req) {
        requirePersonalTaskGroup(id, userId);
        List<Map<String, Object>> candidates = jdbc.query("select id,user_id userId,team_id teamId,scope,name,sort_order sortOrder,is_default isDefault,created_at createdAt from task_group where user_id=? and scope='personal' and id<>? and deleted_at is null order by sort_order,id", taskGroupMapper(), userId, id);
        if (candidates.isEmpty()) throw new BusinessException(400, "at least one task group is required");
        Map<String, Object> target;
        if (req != null && req.get("targetGroupId") != null && !String.valueOf(req.get("targetGroupId")).isBlank()) {
            target = requirePersonalTaskGroup(number(req.get("targetGroupId")), userId);
            if (longValue(target.get("id")) == id) throw new BusinessException(400, "targetGroupId must be another task group");
        } else target = candidates.get(0);
        List<Long> scheduleIds = jdbc.queryForList("select id from schedule where user_id=? and group_id=? and deleted_at is null order by sort_order,id", Long.class, userId, id);
        int sortOrder = nextScheduleSortOrder(userId, longValue(target.get("id")));
        for (Long scheduleId : scheduleIds) {
            jdbc.update("update schedule set group_id=?, group_name=?, sort_order=? where id=? and user_id=?", target.get("id"), target.get("name"), sortOrder, scheduleId, userId);
            sortOrder += 10;
        }
        jdbc.update("update task_group set deleted_at=utc_timestamp() where id=? and user_id=? and scope='personal' and deleted_at is null", id, userId);
    }

    private RowMapper<Map<String, Object>> scheduleMapper() {
        return (rs, i) -> {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", rs.getLong("id")); m.put("userId", rs.getLong("userId")); m.put("title", rs.getString("title")); m.put("description", rs.getString("description"));
            m.put("groupId", rs.getObject("groupId")); m.put("groupName", rs.getString("groupName")); m.put("sortOrder", rs.getInt("sortOrder")); m.put("timeType", rs.getString("timeType"));
            m.put("startTime", iso(rs.getTimestamp("startTime"))); m.put("endTime", iso(rs.getTimestamp("endTime"))); m.put("deadlineTime", iso(rs.getTimestamp("deadlineTime")));
            m.put("status", rs.getString("status")); m.put("createdAt", iso(rs.getTimestamp("createdAt"))); return m;
        };
    }

    private RowMapper<Map<String, Object>> taskGroupMapper() {
        return (rs, i) -> {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", rs.getLong("id")); m.put("userId", rs.getObject("userId")); m.put("teamId", rs.getObject("teamId")); m.put("scope", rs.getString("scope"));
            m.put("name", rs.getString("name")); m.put("sortOrder", rs.getInt("sortOrder")); m.put("isDefault", rs.getBoolean("isDefault")); m.put("createdAt", iso(rs.getTimestamp("createdAt"))); return m;
        };
    }

    private void createDefaultTaskGroups(long userId) {
        String[] names = {"工作", "生活", "团队"};
        for (int i = 0; i < names.length; i++) if (count("select count(*) from task_group where user_id=? and scope='personal' and name=? and deleted_at is null", userId, names[i]) == 0)
            jdbc.update("insert into task_group (user_id,scope,name,sort_order,is_default) values (?,'personal',?,?,?)", userId, names[i], (i + 1) * 10, i == 0);
    }

    private Map<String, Object> resolvePersonalTaskGroup(long userId, Map<String, Object> req) {
        Object rawGroupId = req.get("groupId");
        if (rawGroupId != null && !String.valueOf(rawGroupId).isBlank()) return requirePersonalTaskGroup(number(rawGroupId), userId);
        String groupName = text(req, "groupName").trim();
        if (!groupName.isBlank()) {
            try { return jdbc.queryForObject("select id,user_id userId,team_id teamId,scope,name,sort_order sortOrder,is_default isDefault,created_at createdAt from task_group where user_id=? and scope='personal' and name=? and deleted_at is null", taskGroupMapper(), userId, groupName); }
            catch (EmptyResultDataAccessException ex) { return createTaskGroup(userId, Map.of("scope", "personal", "name", groupName)); }
        }
        createDefaultTaskGroups(userId);
        return jdbc.queryForObject("select id,user_id userId,team_id teamId,scope,name,sort_order sortOrder,is_default isDefault,created_at createdAt from task_group where user_id=? and scope='personal' and deleted_at is null order by sort_order asc, id asc limit 1", taskGroupMapper(), userId);
    }

    private Map<String, Object> requirePersonalTaskGroup(long id, long userId) {
        try { return jdbc.queryForObject("select id,user_id userId,team_id teamId,scope,name,sort_order sortOrder,is_default isDefault,created_at createdAt from task_group where id=? and user_id=? and scope='personal' and deleted_at is null", taskGroupMapper(), id, userId); }
        catch (EmptyResultDataAccessException ex) { throw new BusinessException(404, "task group not found"); }
    }

    private int nextScheduleSortOrder(long userId, long groupId) {
        Integer value = jdbc.queryForObject("select coalesce(max(sort_order),0)+10 from schedule where user_id=? and group_id=? and deleted_at is null", Integer.class, userId, groupId);
        return value == null ? 10 : value;
    }

    private List<Long> idList(Map<String, Object> req, String key) {
        Object raw = req.get(key);
        if (!(raw instanceof List<?> values)) raw = req.get("ids");
        if (!(raw instanceof List<?> values)) throw new BusinessException(400, key + " is required");
        List<Long> ids = new ArrayList<>();
        for (Object value : values) ids.add(number(value));
        return ids;
    }

    private void validateCompleteIds(List<Long> ids, List<Long> expected, String field) {
        if (ids.size() != expected.size() || new HashSet<>(ids).size() != ids.size() || !new HashSet<>(ids).equals(new HashSet<>(expected))) throw new BusinessException(400, field + " must contain the complete set of valid ids");
    }

    private static void reorderSubset(List<Long> all, List<Long> requested, String field) {
        if (requested.isEmpty() || new HashSet<>(requested).size() != requested.size() || !new HashSet<>(all).containsAll(requested)) {
            throw new BusinessException(400, field + " contains invalid ids");
        }
        Set<Long> selected = new HashSet<>(requested);
        List<Integer> positions = new ArrayList<>();
        for (int i = 0; i < all.size(); i++) if (selected.contains(all.get(i))) positions.add(i);
        for (int i = 0; i < positions.size(); i++) all.set(positions.get(i), requested.get(i));
    }

    private void addReminderSummary(Map<String, Object> item, long userId) {
        List<Timestamp> rows = jdbc.query("select min(remind_at) remindAt from reminder where user_id=? and target_type='schedule' and target_id=? and status='pending'", (rs, i) -> rs.getTimestamp("remindAt"), userId, item.get("id"));
        item.put("remindAt", rows.isEmpty() ? "" : iso(rows.get(0)));
    }

    private void addReminderSummaries(List<Map<String, Object>> items, long userId) {
        if (items.isEmpty()) return;
        List<Long> ids = items.stream().map(item -> longValue(item.get("id"))).toList();
        MapSqlParameterSource params = new MapSqlParameterSource("userId", userId).addValue("ids", ids);
        Map<Long, Timestamp> reminders = new HashMap<>();
        List<Map.Entry<Long, Timestamp>> reminderRows = named.query(
                "select target_id targetId,min(remind_at) remindAt from reminder where user_id=:userId and target_type='schedule' and target_id in (:ids) and status='pending' group by target_id",
                params, (rs, i) -> Map.entry(rs.getLong("targetId"), rs.getTimestamp("remindAt")));
        reminderRows.forEach(row -> reminders.put(row.getKey(), row.getValue()));
        items.forEach(item -> item.put("remindAt", iso(reminders.get(longValue(item.get("id"))))));
    }

    private long requiredId(Map<String, Object> req, String key) {
        Object value = req.get(key);
        if (value == null || String.valueOf(value).isBlank()) throw new BusinessException(400, key + " is required");
        return number(value);
    }

    private void validateTaskGroupName(String name) { if (name == null || name.isBlank()) throw new BusinessException(400, "task group name is required"); if (name.length() > 50) throw new BusinessException(400, "task group name is too long"); }
    private void createScheduleReminders(long userId, long scheduleId, Map<String, Object> req) { Object arr = req.get("remindAts"); if (arr instanceof List<?> list) for (Object v : list) insertReminder(userId, "schedule", scheduleId, parseRequiredTime(v, "remindAt")); else if (req.get("remindAt") != null && !String.valueOf(req.get("remindAt")).isBlank()) insertReminder(userId, "schedule", scheduleId, parseRequiredTime(req.get("remindAt"), "remindAt")); }
    private void insertReminder(long userId, String type, long id, Timestamp remindAt) { if (remindAt != null) jdbc.update("insert into reminder (user_id,target_type,target_id,remind_at,status) values (?,?,?,?, 'pending')", userId, type, id, remindAt); }
    private void cancelPendingReminders(String type, long id, Long userId) { if (userId == null) jdbc.update("update reminder set status='cancelled' where target_type=? and target_id=? and status='pending'", type, id); else jdbc.update("update reminder set status='cancelled' where target_type=? and target_id=? and user_id=? and status='pending'", type, id, userId); }
    private static Map<String, Object> pagedResult(List<Map<String, Object>> rows, int total, int page, int size) { return Map.of("list", rows, "total", total, "page", page, "size", size); }
    private Integer count(String sql, Object... args) { Integer n = jdbc.queryForObject(sql, Integer.class, args); return n == null ? 0 : n; }
    private static String iso(Timestamp ts) { return ts == null ? "" : OffsetDateTime.ofInstant(ts.toInstant(), ZoneOffset.UTC).toString(); }
    private static boolean blank(String s) { return s == null || s.isBlank(); }
    private static String text(Map<String, Object> m, String k) { return String.valueOf(m.getOrDefault(k, "")); }
    private static String textOr(Map<String, Object> m, String k, String f) { String v = text(m, k); return blank(v) ? f : v; }
    private static String nullableText(Object v) { if (v == null) return null; String s = String.valueOf(v); return s.isBlank() ? null : s; }
    private static long longValue(Object v) { return ((Number) v).longValue(); }
    private static long number(Object v) { return v instanceof Number n ? n.longValue() : Long.parseLong(String.valueOf(v)); }
    private static Timestamp parseEditableTime(Object value, String field) { if (value == null || String.valueOf(value).isBlank()) return null; return parseRequiredTime(value, field); }
    private static Timestamp parseRequiredTime(Object value, String field) { Timestamp parsed = parseTime(String.valueOf(value)); if (parsed == null) throw new BusinessException(400, field + " is invalid"); return parsed; }
    private static void validateScheduleTimes(String timeType, Timestamp startTime, Timestamp endTime, Timestamp deadlineTime) { if ("point_event".equals(timeType) && startTime == null) throw new BusinessException(400, "startTime is required"); if ("deadline_task".equals(timeType) && deadlineTime == null) throw new BusinessException(400, "deadlineTime is required"); if ("duration_task".equals(timeType) && (startTime == null || endTime == null)) throw new BusinessException(400, "startTime and endTime are required"); if (startTime != null && endTime != null && endTime.before(startTime)) throw new BusinessException(400, "endTime must not be before startTime"); }
    private static Timestamp parseTime(String value) { if (value == null || value.isBlank()) return null; try { return Timestamp.from(OffsetDateTime.parse(value).toInstant()); } catch (Exception ignored) {} try { return Timestamp.valueOf(LocalDateTime.parse(value)); } catch (Exception ignored) {} return null; }
}
