package com.dayliane.schedule;

import com.dayliane.common.ApiResponse;
import com.dayliane.common.DbStore;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/v1/schedules")
public class ScheduleController {
    private final DbStore store;

    public ScheduleController(DbStore store) {
        this.store = store;
    }

    @PostMapping
    public ApiResponse<Map<String, Object>> create(HttpServletRequest request, @RequestBody Map<String, Object> req) {
        long userId = store.requireUser(request.getHeader("Authorization"));
        return ApiResponse.success(store.createSchedule(userId, req));
    }

    @GetMapping
    public ApiResponse<Map<String, Object>> list(HttpServletRequest request,
                                                  @RequestParam(defaultValue = "1") int page,
                                                  @RequestParam(defaultValue = "20") int size,
                                                  @RequestParam(required = false) String status,
                                                  @RequestParam(name = "group_name", required = false) String groupName) {
        long userId = store.requireUser(request.getHeader("Authorization"));
        return ApiResponse.success(store.listSchedules(userId, page, size, status, groupName));
    }

    @GetMapping("/{id}")
    public ApiResponse<Map<String, Object>> detail(HttpServletRequest request, @PathVariable long id) {
        long userId = store.requireUser(request.getHeader("Authorization"));
        return ApiResponse.success(store.requireSchedule(id, userId));
    }

    @PutMapping("/{id}")
    public ApiResponse<Map<String, Object>> update(HttpServletRequest request, @PathVariable long id, @RequestBody Map<String, Object> req) {
        long userId = store.requireUser(request.getHeader("Authorization"));
        return ApiResponse.success(store.updateSchedule(id, userId, req));
    }

    @DeleteMapping("/{id}")
    public ApiResponse<Map<String, Object>> delete(HttpServletRequest request, @PathVariable long id) {
        long userId = store.requireUser(request.getHeader("Authorization"));
        store.deleteSchedule(id, userId);
        return ApiResponse.success(Map.of("ok", true));
    }

    @PutMapping("/{id}/complete")
    public ApiResponse<Map<String, Object>> complete(HttpServletRequest request, @PathVariable long id) { return status(request, id, "completed"); }
    @PutMapping("/{id}/uncomplete")
    public ApiResponse<Map<String, Object>> uncomplete(HttpServletRequest request, @PathVariable long id) { return status(request, id, "pending"); }
    @PutMapping("/{id}/cancel")
    public ApiResponse<Map<String, Object>> cancel(HttpServletRequest request, @PathVariable long id) { return status(request, id, "cancelled"); }
    @PutMapping("/{id}/restore")
    public ApiResponse<Map<String, Object>> restore(HttpServletRequest request, @PathVariable long id) { return status(request, id, "pending"); }

    @GetMapping("/calendar")
    @SuppressWarnings("unchecked")
    public ApiResponse<Map<String, Object>> calendar(HttpServletRequest request, @RequestParam int year, @RequestParam int month) {
        long userId = store.requireUser(request.getHeader("Authorization"));
        YearMonth ym = YearMonth.of(year, month);
        List<Map<String, Object>> all = (List<Map<String, Object>>) store.listSchedules(userId, 1, 500, null, null).get("list");
        List<Map<String, Object>> days = new ArrayList<>();
        for (int day = 1; day <= ym.lengthOfMonth(); day++) {
            LocalDate date = ym.atDay(day);
            List<Map<String, Object>> schedules = all.stream().filter(s -> sameDate(primaryTime(s), date)).toList();
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("date", date.toString());
            row.put("hasSchedule", !schedules.isEmpty());
            row.put("pendingCount", schedules.stream().filter(s -> "pending".equals(s.get("status"))).count());
            row.put("completedCount", schedules.stream().filter(s -> "completed".equals(s.get("status"))).count());
            row.put("schedules", schedules);
            days.add(row);
        }
        return ApiResponse.success(Map.of("year", year, "month", month, "days", days));
    }

    private ApiResponse<Map<String, Object>> status(HttpServletRequest request, long id, String status) {
        long userId = store.requireUser(request.getHeader("Authorization"));
        return ApiResponse.success(store.setScheduleStatus(id, userId, status));
    }

    private static String primaryTime(Map<String, Object> item) {
        String deadline = String.valueOf(item.getOrDefault("deadlineTime", ""));
        return deadline.isBlank() ? String.valueOf(item.getOrDefault("startTime", "")) : deadline;
    }

    private static boolean sameDate(String iso, LocalDate date) {
        return iso != null && iso.startsWith(date.toString());
    }
}
