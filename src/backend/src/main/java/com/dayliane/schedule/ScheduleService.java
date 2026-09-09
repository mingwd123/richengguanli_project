package com.dayliane.schedule;

import com.dayliane.common.BusinessException;
import com.dayliane.fatigue.FatigueService;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.sql.PreparedStatement;
import java.sql.Timestamp;
import java.time.*;
import java.util.*;

@Service
public class ScheduleService {
    private static final String COMPLETED_GROUP_SECTION = "__completed__";
    private static final List<String> LEVEL_SECTION_KEYS = List.of("5", "4", "3", "2", "1");
    private final JdbcTemplate jdbc;
    private final NamedParameterJdbcTemplate named;
    private final FatigueService fatigueService;

    public ScheduleService(JdbcTemplate jdbc, NamedParameterJdbcTemplate named, FatigueService fatigueService) {
        this.jdbc = jdbc;
        this.named = named;
        this.fatigueService = fatigueService;
    }

    @Transactional
    public Map<String, Object> createSchedule(long userId, Map<String, Object> req) {
        String title = text(req, "title");
        String timeType = textOr(req, "timeType", "deadline_task");
        int urgencyLevel = scheduleLevel(req, "urgencyLevel", 3);
        int fatigueLevel = scheduleLevel(req, "fatigueLevel", 3);
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
            PreparedStatement ps = con.prepareStatement("insert into schedule (user_id,title,description,group_id,group_name,sort_order,time_type,start_time,end_time,deadline_time,status,urgency_level,fatigue_level) values (?,?,?,?,?,?,?,?,?,?, 'pending',?,?)", new String[]{"id"});
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
            ps.setInt(11, urgencyLevel);
            ps.setInt(12, fatigueLevel);
            return ps;
        }, keyHolder);
        long id = Objects.requireNonNull(keyHolder.getKey()).longValue();
        createScheduleReminders(userId, id, req);
        Map<String, Object> created = requireSchedule(id, userId);
        recalculateScheduleDates(userId, null, created);
        return created;
    }

    public Map<String, Object> listSchedules(long userId, int page, int size, String status, String groupName, String keyword, String dateFrom, String dateTo) {
        return listSchedules(userId, page, size, status, groupName, keyword, dateFrom, dateTo, "manual", "time", null, null);
    }

    public Map<String, Object> listSchedules(long userId, int page, int size, String status, String groupName, String keyword, String dateFrom, String dateTo, String sort) {
        return listSchedules(userId, page, size, status, groupName, keyword, dateFrom, dateTo, sort, "time", null, null);
    }

    public Map<String, Object> listSchedules(long userId, int page, int size, String status, String groupName, String keyword, String dateFrom, String dateTo, String sort, String viewMode, Integer urgencyLevel, Integer fatigueLevel) {
        return listSchedules(userId, page, size, status, groupName, keyword, dateFrom, dateTo, sort, viewMode, urgencyLevel, fatigueLevel, false);
    }

    public Map<String, Object> listSchedules(long userId, int page, int size, String status, String groupName, String keyword, String dateFrom, String dateTo, String sort, String viewMode, Integer urgencyLevel, Integer fatigueLevel, boolean quickScope) {
        StringBuilder where = new StringBuilder(" from schedule where user_id=:userId and deleted_at is null");
        MapSqlParameterSource p = new MapSqlParameterSource("userId", userId);
        if (!blank(groupName)) { where.append(" and group_name=:groupName"); p.addValue("groupName", groupName); }
        if (!blank(keyword)) { where.append(" and (title like :keyword or description like :keyword2)"); String kw = "%" + keyword + "%"; p.addValue("keyword", kw); p.addValue("keyword2", kw); }
        String safeViewMode = normalizeViewMode(viewMode);
        if (urgencyLevel != null) { validateScheduleLevel(urgencyLevel, "urgencyLevel"); where.append(" and urgency_level=:urgencyLevel"); p.addValue("urgencyLevel", urgencyLevel); }
        if (fatigueLevel != null) { validateScheduleLevel(fatigueLevel, "fatigueLevel"); where.append(" and fatigue_level=:fatigueLevel"); p.addValue("fatigueLevel", fatigueLevel); }
        ZoneId zone = userZone(userId);
        LocalDate fromDate = blank(dateFrom) ? null : parseFilterDate(dateFrom, "dateFrom");
        LocalDate toDate = blank(dateTo) ? null : parseFilterDate(dateTo, "dateTo");
        if (fromDate != null && toDate != null && toDate.isBefore(fromDate)) {
            throw new BusinessException(400, "dateTo must not be before dateFrom");
        }
        if (quickScope) {
            Instant quickNow = Instant.now();
            Instant quickStart = LocalDate.now(zone).atStartOfDay(zone).toInstant();
            Instant quickEnd = LocalDate.now(zone).plusDays(1).atStartOfDay(zone).toInstant();
            where.append(" and status='pending' and ((time_type='deadline_task' and (deadline_time is null or deadline_time<:quickNow or (deadline_time>=:quickStart and deadline_time<:quickEnd)))")
                    .append(" or (time_type='point_event' and (start_time is null or start_time<:quickNow or (start_time>=:quickStart and start_time<:quickEnd)))")
                    .append(" or (time_type='duration_task' and (start_time is null or end_time<:quickNow or (start_time>=:quickStart and start_time<:quickEnd))))");
            p.addValue("quickNow", Timestamp.from(quickNow));
            p.addValue("quickStart", Timestamp.from(quickStart));
            p.addValue("quickEnd", Timestamp.from(quickEnd));
        }
        int safePage = Math.max(1, page);
        int safeSize = Math.min(100, Math.max(1, size));
        List<Map<String, Object>> all = named.query(scheduleSelect() + where, p, scheduleMapper());
        applyCurrentFatigueWeights(userId, all);
        if (fromDate != null || toDate != null) {
            all.removeIf(item -> !matchesDateRange(item, fromDate, toDate, zone));
        }
        if (quickScope) {
            all.removeIf(item -> !isQuickTimelineItem(item, zone));
        }
        Map<String, Long> statusCounts = new LinkedHashMap<>();
        for (Map<String, Object> item : all) {
            String itemStatus = String.valueOf(item.getOrDefault("status", ""));
            statusCounts.merge(itemStatus, 1L, Long::sum);
        }
        if (!blank(status)) all.removeIf(item -> !Objects.equals(status, item.get("status")));
        Map<Long, Integer> groupOrder = personalGroupOrder(userId);
        String safeSort = blank(sort) ? "manual" : sort;
        Comparator<Map<String, Object>> comparator = scheduleComparator(safeViewMode, safeSort, status, zone, groupOrder);
        all.sort(comparator);
        decorateViewRows(all, safeViewMode, zone);
        List<Map<String, Object>> summaries = sectionSummaries(all, safeViewMode, zone, groupOrder);
        int total = all.size();
        int from = Math.min((safePage - 1) * safeSize, total);
        int to = Math.min(from + safeSize, total);
        List<Map<String, Object>> rows = new ArrayList<>(all.subList(from, to));
        addReminderSummaries(rows, userId);
        Map<String, Object> result = new LinkedHashMap<>(pagedResult(rows, total, safePage, safeSize));
        result.put("viewMode", safeViewMode);
        result.put("sectionSummaries", summaries);
        result.put("statusCounts", statusCounts);
        result.put("scope", quickScope ? "quick" : "all");
        long revision = fatigueService.currentDataRevision(userId);
        result.put("dataRevision", revision);
        result.put("revision", String.valueOf(revision));
        return result;
    }

    private static boolean isQuickTimelineItem(Map<String, Object> item, ZoneId zone) {
        return switch (timeSection(item, zone)) {
            case "overdue", "today", "unscheduled" -> true;
            default -> false;
        };
    }

    public List<Map<String, Object>> listSchedulesInRange(long userId, String status, Instant startInclusive, Instant endExclusive) {
        String sql = scheduleSelect() + " from schedule where user_id=? and deleted_at is null"
                + (status == null ? "" : " and status=?")
                + " order by coalesce(deadline_time,end_time,start_time,created_at),id";
        List<Map<String, Object>> rows = status == null
                ? jdbc.query(sql, scheduleMapper(), userId)
                : jdbc.query(sql, scheduleMapper(), userId, status);
        applyCurrentFatigueWeights(userId, rows);
        rows.removeIf(item -> !overlapsRange(item, startInclusive, endExclusive));
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

    private static Comparator<Map<String, Object>> scheduleComparator(String viewMode, String sort, String status, ZoneId zone, Map<Long, Integer> groupOrder) {
        if ("completed".equals(status)) {
            return completedViewComparator(viewMode);
        }
        if (!"manual".equals(sort) || "group".equals(viewMode)) {
            return switch (sort) {
                case "manual" -> manualComparator(zone, groupOrder);
                case "time_asc" -> timeViewComparator(zone);
                case "time_desc" -> Comparator.comparing(ScheduleService::legacyTime, Comparator.nullsFirst(Comparator.reverseOrder()))
                        .thenComparing(Comparator.comparingLong(ScheduleService::scheduleId).reversed());
                case "created_desc" -> Comparator.comparing(ScheduleService::createdTime, Comparator.nullsFirst(Comparator.reverseOrder()))
                        .thenComparing(Comparator.comparingLong(ScheduleService::scheduleId).reversed());
                case "title_asc" -> Comparator.comparing((Map<String, Object> item) -> String.valueOf(item.getOrDefault("title", "")), String.CASE_INSENSITIVE_ORDER)
                        .thenComparingLong(ScheduleService::scheduleId);
                case "urgency_desc" -> urgencyViewComparator(zone);
                case "fatigue_desc" -> fatigueViewComparator(zone);
                default -> throw new BusinessException(400, "sort is invalid");
            };
        }
        return switch (viewMode) {
            case "time" -> timeViewComparator(zone);
            case "group" -> groupViewComparator(zone, groupOrder);
            case "urgency" -> urgencyViewComparator(zone);
            case "fatigue" -> fatigueViewComparator(zone);
            default -> throw new BusinessException(400, "viewMode is invalid");
        };
    }

    private static Comparator<Map<String, Object>> completedViewComparator(String viewMode) {
        Comparator<Map<String, Object>> completedAt = Comparator
                .comparing(ScheduleService::completedTime, Comparator.nullsLast(Comparator.reverseOrder()))
                .thenComparing(Comparator.comparingLong(ScheduleService::scheduleId).reversed());
        return switch (viewMode) {
            case "time", "group" -> completedAt;
            case "urgency" -> Comparator
                    .comparingInt((Map<String, Object> item) -> intValue(item.get("urgencyLevel"), 3)).reversed()
                    .thenComparing(completedAt);
            case "fatigue" -> Comparator
                    .comparingInt((Map<String, Object> item) -> intValue(item.get("fatigueLevel"), 3)).reversed()
                    .thenComparing(completedAt);
            default -> throw new BusinessException(400, "viewMode is invalid");
        };
    }

    private static boolean overlapsRange(Map<String, Object> item, Instant startInclusive, Instant endExclusive) {
        Instant rangeStart = dateRangeStart(item);
        Instant rangeEnd = dateRangeEnd(item);
        if (rangeStart == null && rangeEnd == null) return false;
        if (rangeStart == null) rangeStart = rangeEnd;
        if (rangeEnd == null) rangeEnd = rangeStart;
        return !rangeEnd.isBefore(startInclusive) && rangeStart.isBefore(endExclusive);
    }

    private static Comparator<Map<String, Object>> manualComparator(ZoneId zone, Map<Long, Integer> groupOrder) {
        return Comparator.comparingInt((Map<String, Object> item) -> groupOrderOf(item, groupOrder))
                .thenComparingInt(item -> intValue(item.get("sortOrder"), Integer.MAX_VALUE))
                .thenComparing(ScheduleService::legacyTime, Comparator.nullsLast(Comparator.naturalOrder()))
                .thenComparingLong(ScheduleService::scheduleId);
    }

    private static Comparator<Map<String, Object>> timeViewComparator(ZoneId zone) {
        return Comparator.comparingInt((Map<String, Object> item) -> timeSectionRank(timeSection(item, zone)))
                .thenComparing((a, b) -> Boolean.compare(isOverdueOrMissed(b), isOverdueOrMissed(a)))
                .thenComparing(ScheduleService::effectiveTime, Comparator.nullsLast(Comparator.naturalOrder()))
                .thenComparing(Comparator.comparingInt((Map<String, Object> item) -> intValue(item.get("urgencyLevel"), 3)).reversed())
                .thenComparingLong(ScheduleService::scheduleId);
    }

    private static Comparator<Map<String, Object>> groupViewComparator(ZoneId zone, Map<Long, Integer> groupOrder) {
        return Comparator.comparingInt((Map<String, Object> item) -> groupSectionRank(item, groupOrder))
                .thenComparing((a, b) -> Boolean.compare(isOverdueOrMissed(b), isOverdueOrMissed(a)))
                .thenComparingInt(item -> intValue(item.get("sortOrder"), Integer.MAX_VALUE))
                .thenComparing(ScheduleService::effectiveTime, Comparator.nullsLast(Comparator.naturalOrder()))
                .thenComparingLong(ScheduleService::scheduleId);
    }

    private static Comparator<Map<String, Object>> urgencyViewComparator(ZoneId zone) {
        return Comparator.comparingInt((Map<String, Object> item) -> intValue(item.get("urgencyLevel"), 3)).reversed()
                .thenComparing((a, b) -> Boolean.compare(isOverdueOrMissed(b), isOverdueOrMissed(a)))
                .thenComparing(ScheduleService::effectiveTime, Comparator.nullsLast(Comparator.naturalOrder()))
                .thenComparing(Comparator.comparingInt((Map<String, Object> item) -> intValue(item.get("fatigueLevel"), 3)).reversed())
                .thenComparingLong(ScheduleService::scheduleId);
    }

    private static Comparator<Map<String, Object>> fatigueViewComparator(ZoneId zone) {
        return Comparator.comparingInt((Map<String, Object> item) -> intValue(item.get("fatigueLevel"), 3)).reversed()
                .thenComparing(Comparator.comparingInt((Map<String, Object> item) -> intValue(item.get("urgencyLevel"), 3)).reversed())
                .thenComparing(ScheduleService::effectiveTime, Comparator.nullsLast(Comparator.naturalOrder()))
                .thenComparingLong(ScheduleService::scheduleId);
    }

    public Map<String, Object> requireSchedule(long id, long userId) {
        try {
            Map<String, Object> item = jdbc.queryForObject(scheduleSelect() + " from schedule where id=? and user_id=? and deleted_at is null", scheduleMapper(), id, userId);
            item.put("fatigueWeight", fatigueService.currentWeight(userId, intValue(item.get("fatigueLevel"), 3)));
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
        int urgencyLevel = req.containsKey("urgencyLevel") ? scheduleLevel(req, "urgencyLevel", 3) : intValue(current.get("urgencyLevel"), 3);
        int fatigueLevel = req.containsKey("fatigueLevel") ? scheduleLevel(req, "fatigueLevel", 3) : intValue(current.get("fatigueLevel"), 3);
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
        jdbc.update("update schedule set title=?, description=?, group_id=coalesce(?,group_id), group_name=coalesce(?,group_name), sort_order=coalesce(?,sort_order), time_type=?, start_time=?, end_time=?, deadline_time=?, urgency_level=?, fatigue_level=? where id=? and user_id=?",
                title, description, group == null ? null : longValue(group.get("id")), group == null ? null : String.valueOf(group.get("name")), sortOrder, timeType, startTime, endTime, deadlineTime, urgencyLevel, fatigueLevel, id, userId);
        if (req.containsKey("remindAt") || req.containsKey("remindAts")) {
            cancelRestorableReminders("schedule", id, userId);
            createScheduleReminders(userId, id, req);
            if (!"pending".equals(current.get("status"))) pausePendingReminders("schedule", id, userId);
        }
        Map<String, Object> updated = requireSchedule(id, userId);
        recalculateScheduleDates(userId, current, updated);
        return updated;
    }

    @Transactional
    public void deleteSchedule(long id, long userId) {
        Map<String, Object> current = requireSchedule(id, userId);
        jdbc.update("update schedule set deleted_at=utc_timestamp(), deleted_by=? where id=?", userId, id);
        cancelRestorableReminders("schedule", id, userId);
        recalculateScheduleDates(userId, current, null);
    }

    @Transactional
    public Map<String, Object> setScheduleStatus(long id, long userId, String status) {
        Map<String, Object> schedule = requireSchedule(id, userId);
        String current = String.valueOf(schedule.get("status"));
        if (!List.of("pending", "completed", "cancelled").contains(status)) throw new BusinessException(400, "status is invalid");
        if (current.equals(status)) throw new BusinessException(400, "schedule is already " + status);
        if (!"pending".equals(current) && !"pending".equals(status)) throw new BusinessException(400, "schedule must be restored before changing to " + status);
        if ("completed".equals(status)) {
            Integer level = jdbc.queryForObject("select fatigue_level from schedule where id=? and user_id=?", Integer.class, id, userId);
            int completedLevel = level == null ? 3 : level;
            jdbc.update("update schedule set status=?, completed_at=utc_timestamp(), completed_fatigue_level=?, completed_fatigue_weight=? where id=? and user_id=?",
                    status, completedLevel, fatigueService.currentWeight(userId, completedLevel), id, userId);
        } else if ("pending".equals(status)) {
            jdbc.update("update schedule set status=?, completed_at=null, completed_fatigue_level=null, completed_fatigue_weight=null where id=? and user_id=?", status, id, userId);
        } else {
            jdbc.update("update schedule set status=? where id=? and user_id=?", status, id, userId);
        }
        if (List.of("completed", "cancelled").contains(status)) pausePendingReminders("schedule", id, userId);
        else resumePausedReminders("schedule", id, userId);
        Map<String, Object> updated = requireSchedule(id, userId);
        recalculateScheduleDates(userId, schedule, updated);
        return Map.of("id", id, "status", status);
    }

    @Transactional
    public Map<String, Object> sortTaskGroups(long userId, Map<String, Object> req) {
        createDefaultTaskGroups(userId);
        List<Long> ids = idList(req, "groupIds");
        validateCompleteIds(ids, jdbc.queryForList("select id from task_group where user_id=? and scope='personal' and deleted_at is null order by sort_order,id", Long.class, userId), "groupIds");
        for (int i = 0; i < ids.size(); i++) jdbc.update("update task_group set sort_order=? where id=? and user_id=? and scope='personal' and deleted_at is null", (i + 1) * 10, ids.get(i), userId);
        fatigueService.touchDataRevision(userId);
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
        fatigueService.touchDataRevision(userId);
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
        fatigueService.touchDataRevision(userId);
        return Map.of("scheduleIds", all);
    }

    @Transactional
    public Map<String, Object> moveScheduleGroup(long id, long userId, Map<String, Object> req) {
        requireSchedule(id, userId);
        long groupId = requiredId(req, "groupId");
        Map<String, Object> group = requirePersonalTaskGroup(groupId, userId);
        jdbc.update("update schedule set group_id=?, group_name=?, sort_order=? where id=? and user_id=? and deleted_at is null", groupId, group.get("name"), nextScheduleSortOrder(userId, groupId), id, userId);
        fatigueService.touchDataRevision(userId);
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
        fatigueService.touchDataRevision(userId);
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
        fatigueService.touchDataRevision(userId);
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
        fatigueService.touchDataRevision(userId);
    }

    private RowMapper<Map<String, Object>> scheduleMapper() {
        return (rs, i) -> {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", rs.getLong("id")); m.put("userId", rs.getLong("userId")); m.put("title", rs.getString("title")); m.put("description", rs.getString("description"));
            m.put("groupId", rs.getObject("groupId")); m.put("groupName", rs.getString("groupName")); m.put("sortOrder", rs.getInt("sortOrder")); m.put("timeType", rs.getString("timeType"));
            m.put("startTime", iso(rs.getTimestamp("startTime"))); m.put("endTime", iso(rs.getTimestamp("endTime"))); m.put("deadlineTime", iso(rs.getTimestamp("deadlineTime")));
            int urgency = rs.getInt("urgencyLevel");
            int fatigue = rs.getInt("fatigueLevel");
            m.put("urgencyLevel", urgency); m.put("urgencyLabel", urgencyLabel(urgency));
            m.put("fatigueLevel", fatigue); m.put("fatigueLabel", fatigueLabel(fatigue)); m.put("fatigueWeight", fatigueWeight(fatigue));
            m.put("completedAt", iso(rs.getTimestamp("completedAt"))); m.put("completedFatigueLevel", rs.getObject("completedFatigueLevel"));
            m.put("completedFatigueWeight", rs.getObject("completedFatigueWeight"));
            m.put("status", rs.getString("status")); m.put("createdAt", iso(rs.getTimestamp("createdAt"))); return m;
        };
    }

    private static String scheduleSelect() {
        return "select id,user_id userId,title,description,group_id groupId,group_name groupName,sort_order sortOrder,time_type timeType,start_time startTime,end_time endTime,deadline_time deadlineTime,status,urgency_level urgencyLevel,fatigue_level fatigueLevel,completed_at completedAt,completed_fatigue_level completedFatigueLevel,completed_fatigue_weight completedFatigueWeight,created_at createdAt";
    }

    private Map<Long, Integer> personalGroupOrder(long userId) {
        Map<Long, Integer> order = new HashMap<>();
        jdbc.query("select id,sort_order from task_group where user_id=? and scope='personal' and deleted_at is null order by sort_order,id",
                (rs, i) -> {
                    order.put(rs.getLong("id"), rs.getInt("sort_order"));
                    return null;
                }, userId);
        return order;
    }

    private static boolean matchesDateRange(Map<String, Object> item, LocalDate from, LocalDate to, ZoneId zone) {
        Instant rangeStart = dateRangeStart(item);
        Instant rangeEnd = dateRangeEnd(item);
        if (rangeStart == null && rangeEnd == null) return false;
        if (rangeStart == null) rangeStart = rangeEnd;
        if (rangeEnd == null) rangeEnd = rangeStart;
        Instant fromInstant = from == null ? null : from.atStartOfDay(zone).toInstant();
        Instant toExclusive = to == null ? null : to.plusDays(1).atStartOfDay(zone).toInstant();
        return (fromInstant == null || !rangeEnd.isBefore(fromInstant))
                && (toExclusive == null || rangeStart.isBefore(toExclusive));
    }

    private static Instant dateRangeStart(Map<String, Object> item) {
        return switch (String.valueOf(item.getOrDefault("timeType", ""))) {
            case "duration_task", "point_event" -> instantValue(item.get("startTime"));
            case "deadline_task" -> instantValue(item.get("deadlineTime"));
            default -> instantValue(item.get("createdAt"));
        };
    }

    private static Instant dateRangeEnd(Map<String, Object> item) {
        return switch (String.valueOf(item.getOrDefault("timeType", ""))) {
            case "duration_task" -> instantValue(item.get("endTime"));
            case "point_event" -> instantValue(item.get("startTime"));
            case "deadline_task" -> instantValue(item.get("deadlineTime"));
            default -> instantValue(item.get("createdAt"));
        };
    }

    private static void decorateViewRows(List<Map<String, Object>> rows, String viewMode, ZoneId zone) {
        for (Map<String, Object> item : rows) {
            String raw = sectionRaw(item, viewMode, zone);
            boolean overdue = isOverdueOrMissed(item);
            item.put("sectionKey", sectionKey(viewMode, raw));
            item.put("sectionLabel", sectionLabel(viewMode, raw));
            item.put("isOverdue", overdue);
            item.put("timeStatus", overdue ? ("point_event".equals(item.get("timeType")) ? "missed" : "overdue") : "");
            item.put("effectiveTime", iso(effectiveTime(item)));
        }
    }

    private static List<Map<String, Object>> sectionSummaries(List<Map<String, Object>> items, String viewMode, ZoneId zone, Map<Long, Integer> groupOrder) {
        Map<String, List<Map<String, Object>>> grouped = new LinkedHashMap<>();
        Map<String, Integer> ranks = new HashMap<>();
        for (Map<String, Object> item : items) {
            String raw = sectionRaw(item, viewMode, zone);
            grouped.computeIfAbsent(raw, ignored -> new ArrayList<>()).add(item);
            ranks.merge(raw, sectionRank(item, viewMode, zone, groupOrder), Math::min);
        }
        List<String> keys;
        if ("urgency".equals(viewMode) || "fatigue".equals(viewMode)) {
            keys = LEVEL_SECTION_KEYS;
        } else {
            keys = new ArrayList<>(grouped.keySet());
            keys.sort(Comparator.comparingInt((String key) -> ranks.getOrDefault(key, Integer.MAX_VALUE)).thenComparing(key -> key));
        }
        List<Map<String, Object>> summaries = new ArrayList<>();
        for (String raw : keys) {
            List<Map<String, Object>> section = grouped.getOrDefault(raw, List.of());
            BigDecimal plannedLoad = section.stream()
                    .filter(item -> "pending".equals(item.get("status")))
                    .map(item -> decimalValue(item.get("fatigueWeight"), BigDecimal.valueOf(fatigueWeight(intValue(item.get("fatigueLevel"), 3)))))
                    .reduce(BigDecimal.ZERO, BigDecimal::add);
            BigDecimal completedLoad = section.stream()
                    .filter(item -> "completed".equals(item.get("status")))
                    .map(ScheduleService::completedLoad)
                    .reduce(BigDecimal.ZERO, BigDecimal::add);
            long pendingCount = section.stream().filter(item -> "pending".equals(item.get("status"))).count();
            long completedCount = section.stream().filter(item -> "completed".equals(item.get("status"))).count();
            Map<String, Object> summary = new LinkedHashMap<>();
            summary.put("key", sectionKey(viewMode, raw));
            summary.put("label", sectionLabel(viewMode, raw));
            summary.put("total", section.size());
            summary.put("pendingCount", pendingCount);
            summary.put("completedCount", completedCount);
            summary.put("plannedLoad", plannedLoad);
            summary.put("completedLoad", completedLoad);
            summaries.add(summary);
        }
        return summaries;
    }

    private static BigDecimal completedLoad(Map<String, Object> item) {
        Object snapshot = item.get("completedFatigueWeight");
        if (snapshot != null) return decimalValue(snapshot, BigDecimal.ZERO);
        return decimalValue(item.get("fatigueWeight"),
                BigDecimal.valueOf(fatigueWeight(intValue(item.get("completedFatigueLevel"), intValue(item.get("fatigueLevel"), 3)))));
    }

    private static String sectionKey(String viewMode, String raw) {
        return switch (viewMode) {
            case "urgency" -> "urgency:" + raw;
            case "fatigue" -> "fatigue:" + raw;
            case "group" -> COMPLETED_GROUP_SECTION.equals(raw) ? "group:completed" : "group:" + raw;
            default -> "time:" + raw;
        };
    }

    private static String sectionLabel(String viewMode, String raw) {
        if ("urgency".equals(viewMode)) return urgencyLabel(Integer.parseInt(raw));
        if ("fatigue".equals(viewMode)) return fatigueLabel(Integer.parseInt(raw));
        if ("group".equals(viewMode)) return COMPLETED_GROUP_SECTION.equals(raw) ? "已完成" : raw;
        return switch (raw) {
            case "overdue" -> "已逾期或已错过";
            case "today" -> "今天";
            case "tomorrow" -> "明天";
            case "this_week" -> "本周";
            case "future" -> "以后";
            case "unscheduled" -> "未安排时间";
            case "completed" -> "已完成";
            default -> raw;
        };
    }

    private static String sectionRaw(Map<String, Object> item, String viewMode, ZoneId zone) {
        return switch (viewMode) {
            case "urgency" -> String.valueOf(intValue(item.get("urgencyLevel"), 3));
            case "fatigue" -> String.valueOf(intValue(item.get("fatigueLevel"), 3));
            case "group" -> "completed".equals(item.get("status")) ? COMPLETED_GROUP_SECTION : groupName(item);
            default -> timeSection(item, zone);
        };
    }

    private static int sectionRank(Map<String, Object> item, String viewMode, ZoneId zone, Map<Long, Integer> groupOrder) {
        return switch (viewMode) {
            case "urgency" -> 6 - intValue(item.get("urgencyLevel"), 3);
            case "fatigue" -> 6 - intValue(item.get("fatigueLevel"), 3);
            case "group" -> groupSectionRank(item, groupOrder);
            default -> timeSectionRank(timeSection(item, zone));
        };
    }

    private static int groupOrderOf(Map<String, Object> item, Map<Long, Integer> groupOrder) {
        if ("completed".equals(item.get("status"))) return Integer.MAX_VALUE;
        Object rawId = item.get("groupId");
        if (rawId instanceof Number number && groupOrder.containsKey(number.longValue())) return groupOrder.get(number.longValue());
        return Integer.MAX_VALUE - 1;
    }

    private static int groupSectionRank(Map<String, Object> item, Map<Long, Integer> groupOrder) {
        return groupOrderOf(item, groupOrder);
    }

    private static int timeSectionRank(String section) {
        return switch (section) {
            case "overdue" -> 0;
            case "today" -> 1;
            case "tomorrow" -> 2;
            case "this_week" -> 3;
            case "future" -> 4;
            case "unscheduled" -> 5;
            case "completed" -> 6;
            default -> 7;
        };
    }

    private static String timeSection(Map<String, Object> item, ZoneId zone) {
        if ("completed".equals(item.get("status"))) return "completed";
        Instant effective = effectiveTime(item);
        if (effective == null) return "unscheduled";
        if (isOverdueOrMissed(item)) return "overdue";
        LocalDate date = effective.atZone(zone).toLocalDate();
        LocalDate today = LocalDate.now(zone);
        if (date.equals(today)) return "today";
        if (date.equals(today.plusDays(1))) return "tomorrow";
        LocalDate weekEnd = today.plusDays(7L - today.getDayOfWeek().getValue());
        return !date.isAfter(weekEnd) ? "this_week" : "future";
    }

    private static boolean isOverdueOrMissed(Map<String, Object> item) {
        if (!"pending".equals(item.get("status"))) return false;
        Instant deadline = overdueTime(item);
        return deadline != null && deadline.isBefore(Instant.now());
    }

    private static Instant overdueTime(Map<String, Object> item) {
        return switch (String.valueOf(item.getOrDefault("timeType", ""))) {
            case "deadline_task" -> instantValue(item.get("deadlineTime"));
            case "duration_task" -> instantValue(item.get("endTime"));
            case "point_event" -> instantValue(item.get("startTime"));
            default -> effectiveTime(item);
        };
    }

    private static String groupName(Map<String, Object> item) {
        String name = String.valueOf(item.getOrDefault("groupName", "")).trim();
        return name.isBlank() ? "未分组" : name;
    }

    private static Instant effectiveTime(Map<String, Object> item) {
        String timeType = String.valueOf(item.getOrDefault("timeType", ""));
        return switch (timeType) {
            case "deadline_task" -> instantValue(item.get("deadlineTime"));
            case "duration_task", "point_event" -> instantValue(item.get("startTime"));
            default -> legacyTime(item);
        };
    }

    private static Instant legacyTime(Map<String, Object> item) {
        Instant deadline = instantValue(item.get("deadlineTime"));
        if (deadline != null) return deadline;
        Instant end = instantValue(item.get("endTime"));
        if (end != null) return end;
        Instant start = instantValue(item.get("startTime"));
        if (start != null) return start;
        return instantValue(item.get("createdAt"));
    }

    private static Instant createdTime(Map<String, Object> item) {
        return instantValue(item.get("createdAt"));
    }

    private static Instant completedTime(Map<String, Object> item) {
        return instantValue(item.get("completedAt"));
    }

    private static long scheduleId(Map<String, Object> item) {
        Object value = item.get("id");
        return value instanceof Number number ? number.longValue() : Long.parseLong(String.valueOf(value));
    }

    private static String normalizeViewMode(String value) {
        String mode = blank(value) ? "time" : value;
        if (!List.of("time", "group", "urgency", "fatigue").contains(mode)) throw new BusinessException(400, "viewMode is invalid");
        return mode;
    }

    private static int scheduleLevel(Map<String, Object> req, String field, int fallback) {
        if (!req.containsKey(field)) return fallback;
        Object value = req.get(field);
        if (value == null) throw new BusinessException(400, field + " is invalid");
        if (!(value instanceof Byte || value instanceof Short || value instanceof Integer || value instanceof Long)) throw new BusinessException(400, field + " is invalid");
        long parsed = ((Number) value).longValue();
        if (parsed < Integer.MIN_VALUE || parsed > Integer.MAX_VALUE) throw new BusinessException(400, field + " is invalid");
        int level = (int) parsed;
        validateScheduleLevel(level, field);
        return level;
    }

    private static void validateScheduleLevel(Integer value, String field) {
        if (value == null || value < 1 || value > 5) throw new BusinessException(400, field + " is invalid");
    }

    private static int intValue(Object value, int fallback) { return value instanceof Number n ? n.intValue() : fallback; }

    private static int fatigueWeight(int level) {
        return switch (level) { case 1 -> 1; case 2 -> 2; case 3 -> 3; case 4 -> 5; case 5 -> 8; default -> 3; };
    }

    private static String urgencyLabel(int level) {
        return switch (level) { case 1 -> "不紧急"; case 2 -> "较低"; case 3 -> "普通"; case 4 -> "紧急"; case 5 -> "非常紧急"; default -> "普通"; };
    }

    private static String fatigueLabel(int level) {
        return switch (level) { case 1 -> "几乎不累"; case 2 -> "轻微消耗"; case 3 -> "一般"; case 4 -> "比较劳累"; case 5 -> "非常劳累"; default -> "一般"; };
    }

    private void recalculateScheduleDates(long userId, Map<String, Object> before, Map<String, Object> after) {
        ZoneId zone = userZone(userId);
        List<LocalDate> dates = new ArrayList<>();
        if (before != null) {
            LocalDate planned = plannedDate(before, zone);
            if (planned != null) dates.add(planned);
            LocalDate completed = completedDate(before, zone);
            if (completed != null) dates.add(completed);
        }
        if (after != null) {
            LocalDate planned = plannedDate(after, zone);
            if (planned != null) dates.add(planned);
            LocalDate completed = completedDate(after, zone);
            if (completed != null) dates.add(completed);
        }
        if (dates.isEmpty()) fatigueService.touchDataRevision(userId);
        else fatigueService.recalculateDates(userId, dates, actualLoadChanged(before, after));
    }

    private void applyCurrentFatigueWeights(long userId, List<Map<String, Object>> rows) {
        Map<Integer, BigDecimal> cache = new HashMap<>();
        for (Map<String, Object> row : rows) {
            int level = intValue(row.get("fatigueLevel"), 3);
            row.put("fatigueWeight", cache.computeIfAbsent(level, value -> fatigueService.currentWeight(userId, value)));
        }
    }

    private static boolean actualLoadChanged(Map<String, Object> before, Map<String, Object> after) {
        boolean beforeCompleted = before != null && "completed".equals(before.get("status"));
        boolean afterCompleted = after != null && "completed".equals(after.get("status"));
        if (beforeCompleted != afterCompleted) return true;
        if (!beforeCompleted) return false;
        return !Objects.equals(before.get("completedAt"), after.get("completedAt"))
                || !Objects.equals(before.get("completedFatigueLevel"), after.get("completedFatigueLevel"))
                || decimalValue(before.get("completedFatigueWeight"), BigDecimal.ZERO)
                .compareTo(decimalValue(after.get("completedFatigueWeight"), BigDecimal.ZERO)) != 0;
    }

    private static BigDecimal decimalValue(Object value, BigDecimal fallback) {
        if (value instanceof BigDecimal decimal) return decimal;
        if (value == null) return fallback;
        try { return new BigDecimal(String.valueOf(value)); }
        catch (NumberFormatException ignored) { return fallback; }
    }

    private static LocalDate plannedDate(Map<String, Object> item, ZoneId zone) {
        Instant instant = effectiveTime(item);
        return instant == null ? null : instant.atZone(zone).toLocalDate();
    }

    private static LocalDate completedDate(Map<String, Object> item, ZoneId zone) {
        Instant instant = instantValue(item.get("completedAt"));
        return instant == null ? null : instant.atZone(zone).toLocalDate();
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
    private void pausePendingReminders(String type, long id, long userId) { jdbc.update("update reminder set status='paused' where target_type=? and target_id=? and user_id=? and status='pending'", type, id, userId); }
    private void resumePausedReminders(String type, long id, long userId) {
        jdbc.update("update reminder set status='pending' where target_type=? and target_id=? and user_id=? and status='paused' and remind_at>utc_timestamp()", type, id, userId);
        jdbc.update("update reminder set status='cancelled' where target_type=? and target_id=? and user_id=? and status='paused' and remind_at<=utc_timestamp()", type, id, userId);
    }
    private void cancelRestorableReminders(String type, long id, long userId) { jdbc.update("update reminder set status='cancelled' where target_type=? and target_id=? and user_id=? and status in ('pending','paused')", type, id, userId); }
    private static Map<String, Object> pagedResult(List<Map<String, Object>> rows, int total, int page, int size) { return Map.of("list", rows, "total", total, "page", page, "size", size); }
    private Integer count(String sql, Object... args) { Integer n = jdbc.queryForObject(sql, Integer.class, args); return n == null ? 0 : n; }
    private static String iso(Timestamp ts) { return ts == null ? "" : OffsetDateTime.ofInstant(ts.toInstant(), ZoneOffset.UTC).toString(); }
    private static String iso(Instant instant) { return instant == null ? "" : OffsetDateTime.ofInstant(instant, ZoneOffset.UTC).toString(); }
    private static boolean blank(String s) { return s == null || s.isBlank(); }
    private static LocalDate parseFilterDate(String value, String field) { try { return LocalDate.parse(value); } catch (DateTimeException ex) { throw new BusinessException(400, field + " is invalid"); } }
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
