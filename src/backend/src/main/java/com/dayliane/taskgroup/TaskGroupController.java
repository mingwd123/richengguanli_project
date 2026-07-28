package com.dayliane.taskgroup;

import com.dayliane.auth.AuthService;
import com.dayliane.common.ApiResponse;
import com.dayliane.schedule.ScheduleService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/api/v1/task-groups")
public class TaskGroupController {
    private final ScheduleService scheduleService;
    private final AuthService authService;

    public TaskGroupController(ScheduleService scheduleService, AuthService authService) {
        this.scheduleService = scheduleService;
        this.authService = authService;
    }

    @GetMapping
    public ApiResponse<Map<String, Object>> list(HttpServletRequest request, @RequestParam(defaultValue = "personal") String scope) {
        long userId = authService.requireUser(request.getHeader("Authorization"));
        return ApiResponse.success(scheduleService.listTaskGroups(userId, scope));
    }

    @PostMapping
    public ApiResponse<Map<String, Object>> create(HttpServletRequest request, @RequestBody Map<String, Object> req) {
        long userId = authService.requireUser(request.getHeader("Authorization"));
        return ApiResponse.success(scheduleService.createTaskGroup(userId, req));
    }

    @PutMapping("/sort")
    public ApiResponse<Map<String, Object>> sort(HttpServletRequest request, @RequestBody Map<String, Object> req) {
        long userId = authService.requireUser(request.getHeader("Authorization"));
        return ApiResponse.success(scheduleService.sortTaskGroups(userId, req));
    }

    @PutMapping("/{id}")
    public ApiResponse<Map<String, Object>> update(HttpServletRequest request, @PathVariable long id, @RequestBody Map<String, Object> req) {
        long userId = authService.requireUser(request.getHeader("Authorization"));
        return ApiResponse.success(scheduleService.updateTaskGroup(id, userId, req));
    }

    @DeleteMapping("/{id}")
    public ApiResponse<Map<String, Object>> delete(HttpServletRequest request, @PathVariable long id, @RequestBody(required = false) Map<String, Object> req) {
        long userId = authService.requireUser(request.getHeader("Authorization"));
        scheduleService.deleteTaskGroup(id, userId, req);
        return ApiResponse.success(Map.of("ok", true));
    }
}
