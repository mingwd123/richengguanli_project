package com.dayliane.admin;

import com.dayliane.auth.AuthService;
import com.dayliane.common.ApiResponse;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/api/v1/admin")
public class AdminController {
    private final AdminService adminService;
    private final AuthService authService;

    public AdminController(AdminService adminService, AuthService authService) {
        this.adminService = adminService;
        this.authService = authService;
    }

    @PostMapping("/auth/login")
    public ApiResponse<Map<String, Object>> login(HttpServletRequest request, @RequestBody Map<String, Object> req) {
        return ApiResponse.success(authService.adminLogin(text(req, "username"), text(req, "password"), clientIp(request)));
    }

    @GetMapping("/profile")
    public ApiResponse<Map<String, Object>> profile(HttpServletRequest request) {
        long adminId = authService.requireAdmin(request.getHeader("Authorization"));
        return ApiResponse.success(adminService.adminView(adminId));
    }

    @GetMapping("/users")
    public ApiResponse<Map<String, Object>> users(HttpServletRequest request, @RequestParam(defaultValue = "1") int page, @RequestParam(defaultValue = "20") int size,
                                                  @RequestParam(required = false) String keyword, @RequestParam(required = false) String status,
                                                  @RequestParam(required = false) String dateFrom, @RequestParam(required = false) String dateTo) {
        authService.requireAdmin(request.getHeader("Authorization"));
        return ApiResponse.success(adminService.adminList("users", page, size, keyword, status, dateFrom, dateTo));
    }

    @PutMapping("/users/{id}/status")
    public ApiResponse<Map<String, Object>> userStatus(HttpServletRequest request, @PathVariable long id, @RequestBody Map<String, Object> req) {
        long adminId = authService.requireAdmin(request.getHeader("Authorization"));
        return ApiResponse.success(adminService.adminSetUserStatus(adminId, id, String.valueOf(req.getOrDefault("status", "active")), clientIp(request), userAgent(request)));
    }

    @GetMapping("/admin-users")
    public ApiResponse<Map<String, Object>> adminUsers(HttpServletRequest request, @RequestParam(defaultValue = "1") int page, @RequestParam(defaultValue = "20") int size) {
        authService.requireAdmin(request.getHeader("Authorization"));
        return ApiResponse.success(adminService.adminList("adminUsers", page, size));
    }

    @PostMapping("/admin-users")
    public ApiResponse<Map<String, Object>> createAdminUser(HttpServletRequest request, @RequestBody Map<String, Object> req) {
        long adminId = authService.requireAdmin(request.getHeader("Authorization"));
        return ApiResponse.success(adminService.createAdminUser(adminId, req, clientIp(request), userAgent(request)));
    }

    @PutMapping("/admin-users/{id}/status")
    public ApiResponse<Map<String, Object>> adminUserStatus(HttpServletRequest request, @PathVariable long id, @RequestBody Map<String, Object> req) {
        long adminId = authService.requireAdmin(request.getHeader("Authorization"));
        return ApiResponse.success(adminService.adminSetAdminUserStatus(adminId, id, String.valueOf(req.getOrDefault("status", "active")), clientIp(request), userAgent(request)));
    }

    @GetMapping("/teams")
    public ApiResponse<Map<String, Object>> teams(HttpServletRequest request, @RequestParam(defaultValue = "1") int page, @RequestParam(defaultValue = "20") int size,
                                                  @RequestParam(required = false) String keyword, @RequestParam(required = false) String status,
                                                  @RequestParam(required = false) String dateFrom, @RequestParam(required = false) String dateTo) {
        authService.requireAdmin(request.getHeader("Authorization"));
        return ApiResponse.success(adminService.adminList("teams", page, size, keyword, status, dateFrom, dateTo));
    }

    @GetMapping("/schedules")
    public ApiResponse<Map<String, Object>> schedules(HttpServletRequest request, @RequestParam(defaultValue = "1") int page, @RequestParam(defaultValue = "20") int size,
                                                      @RequestParam(required = false) String keyword, @RequestParam(required = false) String status,
                                                      @RequestParam(required = false) String dateFrom, @RequestParam(required = false) String dateTo) {
        authService.requireAdmin(request.getHeader("Authorization"));
        return ApiResponse.success(adminService.adminList("schedules", page, size, keyword, status, dateFrom, dateTo));
    }

    @GetMapping("/team-tasks")
    public ApiResponse<Map<String, Object>> teamTasks(HttpServletRequest request, @RequestParam(defaultValue = "1") int page, @RequestParam(defaultValue = "20") int size,
                                                      @RequestParam(required = false) String keyword, @RequestParam(required = false) String status,
                                                      @RequestParam(required = false) String dateFrom, @RequestParam(required = false) String dateTo) {
        authService.requireAdmin(request.getHeader("Authorization"));
        return ApiResponse.success(adminService.adminList("teamTasks", page, size, keyword, status, dateFrom, dateTo));
    }

    @GetMapping("/notifications")
    public ApiResponse<Map<String, Object>> notifications(HttpServletRequest request, @RequestParam(defaultValue = "1") int page, @RequestParam(defaultValue = "20") int size,
                                                          @RequestParam(required = false) String keyword, @RequestParam(required = false) String status,
                                                          @RequestParam(required = false) String dateFrom, @RequestParam(required = false) String dateTo) {
        authService.requireAdmin(request.getHeader("Authorization"));
        return ApiResponse.success(adminService.adminList("notifications", page, size, keyword, status, dateFrom, dateTo));
    }

    @GetMapping("/reminders")
    public ApiResponse<Map<String, Object>> reminders(HttpServletRequest request, @RequestParam(defaultValue = "1") int page, @RequestParam(defaultValue = "20") int size,
                                                      @RequestParam(required = false) String keyword, @RequestParam(required = false) String status,
                                                      @RequestParam(required = false) String dateFrom, @RequestParam(required = false) String dateTo) {
        authService.requireAdmin(request.getHeader("Authorization"));
        return ApiResponse.success(adminService.adminList("reminders", page, size, keyword, status, dateFrom, dateTo));
    }

    @GetMapping("/users/{id}")
    public ApiResponse<Map<String, Object>> userDetail(HttpServletRequest request, @PathVariable long id) {
        authService.requireAdmin(request.getHeader("Authorization"));
        return ApiResponse.success(adminService.adminUserDetail(id));
    }

    @GetMapping("/teams/{id}")
    public ApiResponse<Map<String, Object>> teamDetail(HttpServletRequest request, @PathVariable long id) {
        authService.requireAdmin(request.getHeader("Authorization"));
        return ApiResponse.success(adminService.adminTeamDetail(id));
    }

    @GetMapping("/schedules/{id}")
    public ApiResponse<Map<String, Object>> scheduleDetail(HttpServletRequest request, @PathVariable long id) {
        authService.requireAdmin(request.getHeader("Authorization"));
        return ApiResponse.success(adminService.adminScheduleDetail(id));
    }

    @GetMapping("/team-tasks/{id}")
    public ApiResponse<Map<String, Object>> teamTaskDetail(HttpServletRequest request, @PathVariable long id) {
        authService.requireAdmin(request.getHeader("Authorization"));
        return ApiResponse.success(adminService.adminTeamTaskDetail(id));
    }

    @GetMapping("/notifications/{id}")
    public ApiResponse<Map<String, Object>> notificationDetail(HttpServletRequest request, @PathVariable long id) {
        authService.requireAdmin(request.getHeader("Authorization"));
        return ApiResponse.success(adminService.adminNotificationDetail(id));
    }

    @GetMapping("/reminders/{id}")
    public ApiResponse<Map<String, Object>> reminderDetail(HttpServletRequest request, @PathVariable long id) {
        authService.requireAdmin(request.getHeader("Authorization"));
        return ApiResponse.success(adminService.adminReminderDetail(id));
    }

    @GetMapping("/operation-logs")
    public ApiResponse<Map<String, Object>> operationLogs(HttpServletRequest request, @RequestParam(defaultValue = "1") int page, @RequestParam(defaultValue = "20") int size,
                                                          @RequestParam(required = false) String adminId, @RequestParam(required = false) String action,
                                                          @RequestParam(required = false) String targetType, @RequestParam(required = false) String dateFrom,
                                                          @RequestParam(required = false) String dateTo) {
        authService.requireAdmin(request.getHeader("Authorization"));
        return ApiResponse.success(adminService.adminOperationLogs(page, size, adminId, action, targetType, dateFrom, dateTo));
    }

    private static String text(Map<String, Object> req, String key) { return String.valueOf(req.getOrDefault(key, "")); }
    private static String userAgent(HttpServletRequest request) { return request.getHeader("User-Agent"); }
    private static String clientIp(HttpServletRequest request) {
        String forwarded = request.getHeader("X-Forwarded-For");
        if (forwarded != null && !forwarded.isBlank()) return forwarded.split(",")[0].trim();
        return request.getRemoteAddr();
    }
}
