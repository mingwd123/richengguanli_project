package com.dayliane.schedule;

import com.dayliane.auth.AuthService;
import com.dayliane.common.ApiResponse;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/api/v1/schedules")
public class ScheduleController {
    private final ScheduleService scheduleService;
    private final AuthService authService;

    public ScheduleController(ScheduleService scheduleService, AuthService authService) {
        this.scheduleService = scheduleService;
        this.authService = authService;
    }

    @PostMapping
    public ApiResponse<Map<String, Object>> create(HttpServletRequest request, @RequestBody Map<String, Object> req) {
        long userId = authService.requireUser(request.getHeader("Authorization"));
        return ApiResponse.success(scheduleService.createSchedule(userId, req));
    }

    @GetMapping
    public ApiResponse<Map<String, Object>> list(HttpServletRequest request,
                                                  @RequestParam(defaultValue = "1") int page,
                                                  @RequestParam(defaultValue = "20") int size,
                                                  @RequestParam(required = false) String status,
                                                  @RequestParam(name = "group_name", required = false) String groupName,
                                                  @RequestParam(required = false) String keyword,
                                                  @RequestParam(required = false) String dateFrom,
                                                  @RequestParam(required = false) String dateTo,
                                                  @RequestParam(defaultValue = "manual") String sort) {
        long userId = authService.requireUser(request.getHeader("Authorization"));
        return ApiResponse.success(scheduleService.listSchedules(userId, page, size, status, groupName, keyword, dateFrom, dateTo, sort));
    }

    @GetMapping("/{id}")
    public ApiResponse<Map<String, Object>> detail(HttpServletRequest request, @PathVariable long id) {
        long userId = authService.requireUser(request.getHeader("Authorization"));
        return ApiResponse.success(scheduleService.requireSchedule(id, userId));
    }

    @PutMapping("/sort")
    public ApiResponse<Map<String, Object>> sort(HttpServletRequest request, @RequestBody Map<String, Object> req) {
        long userId = authService.requireUser(request.getHeader("Authorization"));
        return ApiResponse.success(scheduleService.sortSchedules(userId, req));
    }

    @PutMapping("/completed/sort")
    public ApiResponse<Map<String, Object>> sortCompleted(HttpServletRequest request, @RequestBody Map<String, Object> req) {
        long userId = authService.requireUser(request.getHeader("Authorization"));
        return ApiResponse.success(scheduleService.sortCompletedSchedules(userId, req));
    }

    @PutMapping("/{id}/move-group")
    public ApiResponse<Map<String, Object>> moveGroup(HttpServletRequest request, @PathVariable long id, @RequestBody Map<String, Object> req) {
        long userId = authService.requireUser(request.getHeader("Authorization"));
        return ApiResponse.success(scheduleService.moveScheduleGroup(id, userId, req));
    }

    @PutMapping("/{id}")
    public ApiResponse<Map<String, Object>> update(HttpServletRequest request, @PathVariable long id, @RequestBody Map<String, Object> req) {
        long userId = authService.requireUser(request.getHeader("Authorization"));
        return ApiResponse.success(scheduleService.updateSchedule(id, userId, req));
    }

    @DeleteMapping("/{id}")
    public ApiResponse<Map<String, Object>> delete(HttpServletRequest request, @PathVariable long id) {
        long userId = authService.requireUser(request.getHeader("Authorization"));
        scheduleService.deleteSchedule(id, userId);
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
    public ApiResponse<Map<String, Object>> calendar(HttpServletRequest request, @RequestParam int year, @RequestParam int month) {
        long userId = authService.requireUser(request.getHeader("Authorization"));
        return ApiResponse.success(scheduleService.calendar(userId, year, month));
    }

    private ApiResponse<Map<String, Object>> status(HttpServletRequest request, long id, String status) {
        long userId = authService.requireUser(request.getHeader("Authorization"));
        return ApiResponse.success(scheduleService.setScheduleStatus(id, userId, status));
    }

}
