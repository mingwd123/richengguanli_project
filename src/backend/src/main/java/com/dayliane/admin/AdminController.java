package com.dayliane.admin;

import com.dayliane.auth.AuthService;
import com.dayliane.common.ApiResponse;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/api/v1/admin")
public class AdminController {
    private final AdminService adminService;
    private final AuthService authService;
    private final boolean trustForwardedHeaders;

    public AdminController(AdminService adminService, AuthService authService,
                           @Value("${app.http.trust-forwarded-headers:false}") boolean trustForwardedHeaders) {
        this.adminService = adminService;
        this.authService = authService;
        this.trustForwardedHeaders = trustForwardedHeaders;
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

    @GetMapping("/dashboard/stats")
    public ApiResponse<Map<String, Object>> dashboardStats(HttpServletRequest request) {
        authService.requireAdmin(request.getHeader("Authorization"));
        return ApiResponse.success(adminService.dashboardStats());
    }

    @GetMapping("/users")
    public ApiResponse<Map<String, Object>> users(HttpServletRequest request, @RequestParam(defaultValue = "1") int page, @RequestParam(defaultValue = "20") int size,
                                                  @RequestParam(required = false) String keyword, @RequestParam(required = false) String status,
                                                  @RequestParam(required = false) String dateFrom, @RequestParam(required = false) String dateTo,
                                                  @RequestParam(required = false) String sort) {
        authService.requireAdmin(request.getHeader("Authorization"));
        return ApiResponse.success(adminService.adminList("users", page, size, keyword, status, dateFrom, dateTo, sort));
    }

    @PostMapping("/users")
    public ApiResponse<Map<String, Object>> createUser(HttpServletRequest request, @RequestBody Map<String, Object> req) {
        long adminId = authService.requireAdmin(request.getHeader("Authorization"));
        return ApiResponse.success(adminService.createUser(adminId, req, clientIp(request), userAgent(request)));
    }

    @PutMapping("/users/{id}")
    public ApiResponse<Map<String, Object>> updateUser(HttpServletRequest request, @PathVariable long id,
                                                       @RequestBody Map<String, Object> req) {
        long adminId = authService.requireAdmin(request.getHeader("Authorization"));
        return ApiResponse.success(adminService.updateUser(adminId, id, req, clientIp(request), userAgent(request)));
    }

    @PutMapping("/users/{id}/status")
    public ApiResponse<Map<String, Object>> userStatus(HttpServletRequest request, @PathVariable long id, @RequestBody Map<String, Object> req) {
        long adminId = authService.requireAdmin(request.getHeader("Authorization"));
        return ApiResponse.success(adminService.adminSetUserStatus(adminId, id, String.valueOf(req.getOrDefault("status", "active")), clientIp(request), userAgent(request)));
    }

    @GetMapping("/admin-users")
    public ApiResponse<Map<String, Object>> adminUsers(HttpServletRequest request, @RequestParam(defaultValue = "1") int page, @RequestParam(defaultValue = "20") int size,
                                                        @RequestParam(required = false) String keyword, @RequestParam(required = false) String status,
                                                        @RequestParam(required = false) String dateFrom, @RequestParam(required = false) String dateTo,
                                                        @RequestParam(required = false) String sort) {
        long adminId = authService.requireAdmin(request.getHeader("Authorization"));
        adminService.requireSuperAdmin(adminId);
        return ApiResponse.success(adminService.adminList("adminUsers", page, size, keyword, status, dateFrom, dateTo, sort));
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
                                                  @RequestParam(required = false) String dateFrom, @RequestParam(required = false) String dateTo,
                                                  @RequestParam(required = false) String sort) {
        authService.requireAdmin(request.getHeader("Authorization"));
        return ApiResponse.success(adminService.adminList("teams", page, size, keyword, status, dateFrom, dateTo, sort));
    }

    @GetMapping("/schedules")
    public ApiResponse<Map<String, Object>> schedules(HttpServletRequest request, @RequestParam(defaultValue = "1") int page, @RequestParam(defaultValue = "20") int size,
                                                      @RequestParam(required = false) String keyword, @RequestParam(required = false) String status,
                                                      @RequestParam(required = false) String dateFrom, @RequestParam(required = false) String dateTo,
                                                      @RequestParam(required = false) String sort) {
        authService.requireAdmin(request.getHeader("Authorization"));
        return ApiResponse.success(adminService.adminList("schedules", page, size, keyword, status, dateFrom, dateTo, sort));
    }

    @GetMapping("/team-tasks")
    public ApiResponse<Map<String, Object>> teamTasks(HttpServletRequest request, @RequestParam(defaultValue = "1") int page, @RequestParam(defaultValue = "20") int size,
                                                      @RequestParam(required = false) String keyword, @RequestParam(required = false) String status,
                                                      @RequestParam(required = false) String dateFrom, @RequestParam(required = false) String dateTo,
                                                      @RequestParam(required = false) String sort) {
        authService.requireAdmin(request.getHeader("Authorization"));
        return ApiResponse.success(adminService.adminList("teamTasks", page, size, keyword, status, dateFrom, dateTo, sort));
    }

    @GetMapping("/notifications")
    public ApiResponse<Map<String, Object>> notifications(HttpServletRequest request, @RequestParam(defaultValue = "1") int page, @RequestParam(defaultValue = "20") int size,
                                                          @RequestParam(required = false) String keyword, @RequestParam(required = false) String status,
                                                          @RequestParam(required = false) String dateFrom, @RequestParam(required = false) String dateTo,
                                                          @RequestParam(required = false) String sort) {
        authService.requireAdmin(request.getHeader("Authorization"));
        return ApiResponse.success(adminService.adminList("notifications", page, size, keyword, status, dateFrom, dateTo, sort));
    }

    @GetMapping("/reminders")
    public ApiResponse<Map<String, Object>> reminders(HttpServletRequest request, @RequestParam(defaultValue = "1") int page, @RequestParam(defaultValue = "20") int size,
                                                      @RequestParam(required = false) String keyword, @RequestParam(required = false) String status,
                                                      @RequestParam(required = false) String dateFrom, @RequestParam(required = false) String dateTo,
                                                      @RequestParam(required = false) String sort) {
        authService.requireAdmin(request.getHeader("Authorization"));
        return ApiResponse.success(adminService.adminList("reminders", page, size, keyword, status, dateFrom, dateTo, sort));
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

    @PutMapping("/team-tasks/{id}/cancel")
    public ApiResponse<Map<String, Object>> cancelTeamTask(HttpServletRequest request, @PathVariable long id) {
        long adminId = authService.requireAdmin(request.getHeader("Authorization"));
        return ApiResponse.success(adminService.adminCancelTeamTask(adminId, id, clientIp(request), userAgent(request)));
    }

    @PutMapping("/team-tasks/{id}/restore")
    public ApiResponse<Map<String, Object>> restoreTeamTask(HttpServletRequest request, @PathVariable long id) {
        long adminId = authService.requireAdmin(request.getHeader("Authorization"));
        return ApiResponse.success(adminService.adminRestoreTeamTask(adminId, id, clientIp(request), userAgent(request)));
    }

    @PutMapping("/team-tasks/{id}/assignees/{assigneeId}/status")
    public ApiResponse<Map<String, Object>> correctAssigneeStatus(HttpServletRequest request, @PathVariable long id, @PathVariable long assigneeId, @RequestBody Map<String, Object> req) {
        long adminId = authService.requireAdmin(request.getHeader("Authorization"));
        return ApiResponse.success(adminService.adminCorrectAssigneeStatus(adminId, id, assigneeId, String.valueOf(req.getOrDefault("status", "pending")), clientIp(request), userAgent(request)));
    }

    @PutMapping("/schedules/{id}/status")
    public ApiResponse<Map<String, Object>> setScheduleStatus(HttpServletRequest request, @PathVariable long id, @RequestBody Map<String, Object> req) {
        long adminId = authService.requireAdmin(request.getHeader("Authorization"));
        return ApiResponse.success(adminService.adminSetScheduleStatus(adminId, id, String.valueOf(req.getOrDefault("status", "pending")), clientIp(request), userAgent(request)));
    }

    @GetMapping("/operation-logs")
    public ApiResponse<Map<String, Object>> operationLogs(HttpServletRequest request, @RequestParam(defaultValue = "1") int page, @RequestParam(defaultValue = "20") int size,
                                                          @RequestParam(required = false) String adminId, @RequestParam(required = false) String action,
                                                          @RequestParam(required = false) String targetType, @RequestParam(required = false) String dateFrom,
                                                          @RequestParam(required = false) String dateTo, @RequestParam(required = false) String keyword) {
        authService.requireAdmin(request.getHeader("Authorization"));
        return ApiResponse.success(adminService.adminOperationLogs(page, size, adminId, action, targetType, dateFrom, dateTo, keyword));
    }

    private static String text(Map<String, Object> req, String key) { return String.valueOf(req.getOrDefault(key, "")); }
    private static String userAgent(HttpServletRequest request) { return request.getHeader("User-Agent"); }
    private String clientIp(HttpServletRequest request) {
        if (trustForwardedHeaders) {
            String forwarded = request.getHeader("X-Forwarded-For");
            if (forwarded != null) {
                for (String candidate : forwarded.split(",")) {
                    String selected = candidate.trim();
                    if (selected.isEmpty()) continue;
                    if (selected.length() <= 45) return selected;
                    break;
                }
            }
        }
        String remoteAddress = request.getRemoteAddr();
        if (remoteAddress == null) return "unknown";
        String selected = remoteAddress.trim();
        return selected.isEmpty() || selected.length() > 45 ? "unknown" : selected;
    }
}
