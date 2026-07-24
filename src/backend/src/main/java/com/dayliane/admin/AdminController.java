package com.dayliane.admin;

import com.dayliane.common.ApiResponse;
import com.dayliane.common.DbStore;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/api/v1/admin")
public class AdminController {
    private final DbStore store;

    public AdminController(DbStore store) {
        this.store = store;
    }

    @GetMapping("/profile")
    public ApiResponse<Map<String, Object>> profile(HttpServletRequest request) {
        long userId = store.requireUser(request.getHeader("Authorization"));
        return ApiResponse.success(store.userView(userId));
    }

    @GetMapping("/users")
    public ApiResponse<Map<String, Object>> users(HttpServletRequest request, @RequestParam(defaultValue = "1") int page, @RequestParam(defaultValue = "20") int size) {
        store.requireUser(request.getHeader("Authorization"));
        return ApiResponse.success(store.adminList("users", page, size));
    }

    @PutMapping("/users/{id}/status")
    public ApiResponse<Map<String, Object>> userStatus(HttpServletRequest request, @PathVariable long id, @RequestBody Map<String, Object> req) {
        store.requireUser(request.getHeader("Authorization"));
        return ApiResponse.success(store.adminSetUserStatus(id, String.valueOf(req.getOrDefault("status", "active"))));
    }

    @GetMapping("/teams")
    public ApiResponse<Map<String, Object>> teams(HttpServletRequest request, @RequestParam(defaultValue = "1") int page, @RequestParam(defaultValue = "20") int size) {
        store.requireUser(request.getHeader("Authorization"));
        return ApiResponse.success(store.adminList("teams", page, size));
    }

    @GetMapping("/schedules")
    public ApiResponse<Map<String, Object>> schedules(HttpServletRequest request, @RequestParam(defaultValue = "1") int page, @RequestParam(defaultValue = "20") int size) {
        store.requireUser(request.getHeader("Authorization"));
        return ApiResponse.success(store.adminList("schedules", page, size));
    }

    @GetMapping("/team-tasks")
    public ApiResponse<Map<String, Object>> teamTasks(HttpServletRequest request, @RequestParam(defaultValue = "1") int page, @RequestParam(defaultValue = "20") int size) {
        store.requireUser(request.getHeader("Authorization"));
        return ApiResponse.success(store.adminList("teamTasks", page, size));
    }

    @GetMapping("/notifications")
    public ApiResponse<Map<String, Object>> notifications(HttpServletRequest request, @RequestParam(defaultValue = "1") int page, @RequestParam(defaultValue = "20") int size) {
        store.requireUser(request.getHeader("Authorization"));
        return ApiResponse.success(store.adminList("notifications", page, size));
    }

    @GetMapping("/reminders")
    public ApiResponse<Map<String, Object>> reminders(HttpServletRequest request, @RequestParam(defaultValue = "1") int page, @RequestParam(defaultValue = "20") int size) {
        store.requireUser(request.getHeader("Authorization"));
        return ApiResponse.success(store.adminList("reminders", page, size));
    }
}
