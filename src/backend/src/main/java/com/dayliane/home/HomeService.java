package com.dayliane.home;

import com.dayliane.notification.NotificationService;
import com.dayliane.fatigue.FatigueService;
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
    private final FatigueService fatigueService;

    public HomeService(ScheduleService scheduleService, TeamTaskService teamTaskService,
                       NotificationService notificationService, UserService userService, FatigueService fatigueService) {
        this.scheduleService = scheduleService;
        this.teamTaskService = teamTaskService;
        this.notificationService = notificationService;
        this.userService = userService;
        this.fatigueService = fatigueService;
    }

    public Map<String, Object> today(long userId) {
        return today(userId, Instant.now());
    }

    public Map<String, Object> today(long userId, Instant now) {
        ZoneId zone = userZone(userId);
        LocalDate localToday = now.atZone(zone).toLocalDate();
        Instant startInclusive = localToday.atStartOfDay(zone).toInstant();
        Instant endExclusive = localToday.plusDays(1).atStartOfDay(zone).toInstant();
        List<Map<String, Object>> todaySchedules = scheduleService.listSchedulesInRange(userId, "pending", startInclusive, endExclusive);
        List<Map<String, Object>> todayTasks = teamTaskService.listMyTeamTasksInRange(userId, startInclusive, endExclusive);
        return Map.of(
                "date", localToday.toString(),
                "timezone", zone.getId(),
                "personalSchedules", todaySchedules,
                "teamTasks", todayTasks,
                "unreadNotificationCount", notificationService.unreadCount(userId),
                "groups", buildGroups(todaySchedules, todayTasks),
                "fatigue", fatigueService.daily(userId, localToday)
        );
    }

    public Map<String, Object> upcoming(long userId) {
        return upcoming(userId, Instant.now());
    }

    public Map<String, Object> upcoming(long userId, Instant now) {
        ZoneId zone = userZone(userId);
        LocalDate tomorrow = now.atZone(zone).toLocalDate().plusDays(1);
        LocalDate endExclusive = tomorrow.plusDays(7);
        Instant startInclusive = tomorrow.atStartOfDay(zone).toInstant();
        Instant endInstant = endExclusive.atStartOfDay(zone).toInstant();
        List<Map<String, Object>> schedules = scheduleService.listSchedulesInRange(userId, "pending", startInclusive, endInstant);
        List<Map<String, Object>> tasks = teamTaskService.listMyTeamTasksInRange(userId, startInclusive, endInstant);
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
