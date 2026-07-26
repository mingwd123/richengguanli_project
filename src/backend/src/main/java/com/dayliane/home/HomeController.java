package com.dayliane.home;

import com.dayliane.auth.AuthService;
import com.dayliane.common.ApiResponse;
import com.dayliane.notification.NotificationService;
import com.dayliane.schedule.ScheduleService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.util.*;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/api/v1/home")
public class HomeController {
    private final ScheduleService scheduleService;
    private final NotificationService notificationService;
    private final AuthService authService;

    public HomeController(ScheduleService scheduleService, NotificationService notificationService, AuthService authService) {
        this.scheduleService = scheduleService;
        this.notificationService = notificationService;
        this.authService = authService;
    }

    @SuppressWarnings("unchecked")
    @GetMapping("/today")
    public ApiResponse<Map<String, Object>> today(HttpServletRequest request) {
        long userId = authService.requireUser(request.getHeader("Authorization"));
        LocalDate today = LocalDate.now();
        List<Map<String, Object>> schedules = (List<Map<String, Object>>) scheduleService.listSchedules(userId, 1, 100, null, null).get("list");
        List<Map<String, Object>> todaySchedules = schedules.stream().filter(s -> sameDate(primaryTime(s), today)).collect(Collectors.toList());
        return ApiResponse.success(Map.of(
                "personalSchedules", todaySchedules,
                "teamTasks", List.of(),
                "unreadNotificationCount", notificationService.unreadCount(userId),
                "groups", List.of()
        ));
    }

    @SuppressWarnings("unchecked")
    @GetMapping("/upcoming")
    public ApiResponse<Map<String, Object>> upcoming(HttpServletRequest request) {
        long userId = authService.requireUser(request.getHeader("Authorization"));
        List<Map<String, Object>> schedules = (List<Map<String, Object>>) scheduleService.listSchedules(userId, 1, 100, "pending", null).get("list");
        List<Map<String, Object>> out = new ArrayList<>();
        for (Map<String, Object> s : schedules) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("type", "schedule");
            row.put("id", s.get("id"));
            row.put("title", s.get("title"));
            row.put("deadlineTime", primaryTime(s));
            row.put("groupName", s.get("groupName"));
            out.add(row);
        }
        out.sort(Comparator.comparing(x -> String.valueOf(x.get("deadlineTime"))));
        return ApiResponse.success(Map.of("list", out.stream().limit(20).collect(Collectors.toList())));
    }

    private static String primaryTime(Map<String, Object> item) {
        String deadline = String.valueOf(item.getOrDefault("deadlineTime", ""));
        if (!deadline.isBlank()) return deadline;
        String end = String.valueOf(item.getOrDefault("endTime", ""));
        return end.isBlank() ? String.valueOf(item.getOrDefault("startTime", "")) : end;
    }

    private static boolean sameDate(String iso, LocalDate date) {
        return iso != null && iso.startsWith(date.toString());
    }
}
