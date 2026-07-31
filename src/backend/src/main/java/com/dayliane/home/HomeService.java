package com.dayliane.home;

import com.dayliane.notification.NotificationService;
import com.dayliane.schedule.ScheduleService;
import com.dayliane.teamtask.TeamTaskService;
import com.dayliane.user.UserService;
import org.springframework.stereotype.Service;

import java.time.DateTimeException;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Service
public class HomeService {
    private final ScheduleService scheduleService;
    private final TeamTaskService teamTaskService;
    private final NotificationService notificationService;
    private final UserService userService;

    public HomeService(ScheduleService scheduleService, TeamTaskService teamTaskService,
                       NotificationService notificationService, UserService userService) {
        this.scheduleService = scheduleService;
        this.teamTaskService = teamTaskService;
        this.notificationService = notificationService;
        this.userService = userService;
    }

    public Map<String, Object> today(long userId) {
        return today(userId, Instant.now());
    }

    @SuppressWarnings("unchecked")
    public Map<String, Object> today(long userId, Instant now) {
        ZoneId zone = userZone(userId);
        LocalDate localToday = now.atZone(zone).toLocalDate();
        List<Map<String, Object>> schedules = (List<Map<String, Object>>) scheduleService
                .listSchedules(userId, 1, 100, "pending", null, null, null, null).get("list");
        List<Map<String, Object>> tasks = (List<Map<String, Object>>) teamTaskService
                .listMyTeamTasks(userId, 1, 100, null, null, null, null).get("list");
        List<Map<String, Object>> todaySchedules = schedules.stream()
                .filter(item -> isOnDate(item, localToday, zone)).toList();
        List<Map<String, Object>> todayTasks = tasks.stream()
                .filter(HomeService::isOpenAssignedTask)
                .filter(item -> isOnDate(item, localToday, zone)).toList();
        return Map.of(
                "date", localToday.toString(),
                "timezone", zone.getId(),
                "personalSchedules", todaySchedules,
                "teamTasks", todayTasks,
                "unreadNotificationCount", notificationService.unreadCount(userId),
                "groups", buildGroups(todaySchedules, todayTasks)
        );
    }

    public Map<String, Object> upcoming(long userId) {
        return upcoming(userId, Instant.now());
    }

    @SuppressWarnings("unchecked")
    public Map<String, Object> upcoming(long userId, Instant now) {
        ZoneId zone = userZone(userId);
        LocalDate tomorrow = now.atZone(zone).toLocalDate().plusDays(1);
        LocalDate endExclusive = tomorrow.plusDays(7);
        List<Map<String, Object>> schedules = ((List<Map<String, Object>>) scheduleService
                .listSchedules(userId, 1, 100, "pending", null, null, null, null).get("list")).stream()
                .filter(item -> isInRange(item, tomorrow, endExclusive, zone)).toList();
        List<Map<String, Object>> tasks = ((List<Map<String, Object>>) teamTaskService
                .listMyTeamTasks(userId, 1, 100, null, null, null, null).get("list")).stream()
                .filter(HomeService::isOpenAssignedTask)
                .filter(item -> isInRange(item, tomorrow, endExclusive, zone)).toList();
        List<Map<String, Object>> combined = new ArrayList<>();
        schedules.forEach(item -> combined.add(withSource(item, "schedule")));
        tasks.forEach(item -> combined.add(withSource(item, "team_task")));
        combined.sort(Comparator.comparing(item -> primaryTime(item)));
        return Map.of(
                "timezone", zone.getId(),
                "dateFrom", tomorrow.toString(),
                "dateTo", endExclusive.minusDays(1).toString(),
                "personalSchedules", schedules,
                "teamTasks", tasks,
                "list", combined
        );
    }

    private ZoneId userZone(long userId) {
        String timezone = String.valueOf(userService.userView(userId).getOrDefault("timezone", "Asia/Shanghai"));
        try {
            return ZoneId.of(timezone);
        } catch (DateTimeException ignored) {
            return ZoneId.of("Asia/Shanghai");
        }
    }

    private static boolean isOpenAssignedTask(Map<String, Object> task) {
        String status = String.valueOf(task.getOrDefault("status", ""));
        String assignStatus = String.valueOf(task.getOrDefault("assignStatus", ""));
        return "active".equals(status) && ("pending".equals(assignStatus) || "accepted".equals(assignStatus));
    }

    private static boolean isOnDate(Map<String, Object> item, LocalDate date, ZoneId zone) {
        Instant instant = parseInstant(primaryTime(item), zone);
        return instant != null && instant.atZone(zone).toLocalDate().equals(date);
    }

    private static boolean isInRange(Map<String, Object> item, LocalDate start, LocalDate endExclusive, ZoneId zone) {
        Instant instant = parseInstant(primaryTime(item), zone);
        if (instant == null) return false;
        LocalDate date = instant.atZone(zone).toLocalDate();
        return !date.isBefore(start) && date.isBefore(endExclusive);
    }

    private static Instant parseInstant(String value, ZoneId zone) {
        if (value == null || value.isBlank()) return null;
        try { return OffsetDateTime.parse(value).toInstant(); } catch (DateTimeException ignored) {}
        try { return LocalDateTime.parse(value).atZone(zone).toInstant(); } catch (DateTimeException ignored) {}
        return null;
    }

    private static Map<String, Object> withSource(Map<String, Object> item, String sourceType) {
        Map<String, Object> copy = new LinkedHashMap<>(item);
        copy.put("sourceType", sourceType);
        return copy;
    }

    private static String primaryTime(Map<String, Object> item) {
        String deadline = String.valueOf(item.getOrDefault("deadlineTime", ""));
        if (!deadline.isBlank()) return deadline;
        String end = String.valueOf(item.getOrDefault("endTime", ""));
        return end.isBlank() ? String.valueOf(item.getOrDefault("startTime", "")) : end;
    }

    private static List<Map<String, Object>> buildGroups(List<Map<String, Object>> schedules, List<Map<String, Object>> tasks) {
        Map<String, Integer> counts = new LinkedHashMap<>();
        schedules.forEach(item -> counts.merge(String.valueOf(item.getOrDefault("groupName", "未分组")), 1, Integer::sum));
        tasks.forEach(item -> counts.merge("团队任务", 1, Integer::sum));
        return counts.entrySet().stream().map(entry -> Map.<String, Object>of("name", entry.getKey(), "items", entry.getValue())).toList();
    }
}
