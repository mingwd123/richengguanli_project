package com.dayliane.admin;

import com.dayliane.ai.AiQuotaService;
import com.dayliane.auth.AuthService;
import com.dayliane.common.ApiResponse;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/api/v1/admin")
public class AdminAnalyticsController {
    private final AuthService auth;
    private final AdminAnalyticsService analytics;
    private final AdminSystemStatusService system;
    private final AiQuotaService quota;
    private final AdminService admin;
    private final AdminInspectionService inspection;

    public AdminAnalyticsController(AuthService auth, AdminAnalyticsService analytics, AdminSystemStatusService system,
                                     AiQuotaService quota, AdminService admin, AdminInspectionService inspection) {
        this.auth = auth;
        this.analytics = analytics;
        this.system = system;
        this.quota = quota;
        this.admin = admin;
        this.inspection = inspection;
    }

    @GetMapping("/views/{group}/{metric}/details")
    public ApiResponse<Map<String, Object>> details(HttpServletRequest request, @PathVariable String group,
            @PathVariable String metric, @RequestParam String dateFrom, @RequestParam String dateTo,
            @RequestParam String selector, @RequestParam(defaultValue = "") String series,
            @RequestParam(defaultValue = "1") int page, @RequestParam(defaultValue = "20") int size) {
        requireAdmin(request);
        return ApiResponse.success(inspection.details(group, metric, AnalyticsRange.parse(dateFrom, dateTo), selector, series, page, size));
    }

    @GetMapping("/views/{group}/{metric}/export")
    public ApiResponse<Map<String, Object>> export(HttpServletRequest request, @PathVariable String group,
            @PathVariable String metric, @RequestParam String dateFrom, @RequestParam String dateTo,
            @RequestParam(required = false) String selector, @RequestParam(defaultValue = "") String series,
            @RequestParam(required = false) Long userId) {
        requireAdmin(request);
        return ApiResponse.success(inspection.export(group, metric, AnalyticsRange.parse(dateFrom, dateTo), selector, series, userId));
    }

    @GetMapping("/users/{id}/workspace/{section}")
    public ApiResponse<Map<String, Object>> workspace(HttpServletRequest request, @PathVariable long id,
            @PathVariable String section, @RequestParam(defaultValue = "1") int page, @RequestParam(defaultValue = "20") int size) {
        requireAdmin(request);
        return ApiResponse.success(inspection.workspace(id, section, page, size));
    }

    @PutMapping("/security/events/{id}/review")
    public ApiResponse<Map<String, Object>> review(HttpServletRequest request, @PathVariable long id, @RequestBody Map<String, Object> body) {
        return ApiResponse.success(inspection.review(requireAdmin(request), id, body, request.getRemoteAddr(), request.getHeader("User-Agent")));
    }

    @GetMapping("/ai/usage-logs/{id}")
    public ApiResponse<Map<String, Object>> aiLog(HttpServletRequest request, @PathVariable long id) {
        requireAdmin(request);
        return ApiResponse.success(inspection.aiLog(id));
    }

    @GetMapping("/views/{group}/{metric}")
    public ApiResponse<Map<String, Object>> view(HttpServletRequest request, @PathVariable String group,
            @PathVariable String metric, @RequestParam(required = false) String dateFrom,
            @RequestParam(required = false) String dateTo, @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size) {
        requireAdmin(request);
        return ApiResponse.success(analytics.view(group, metric, AnalyticsRange.parse(dateFrom, dateTo), page, size));
    }

    @GetMapping("/ops/system-status")
    public ApiResponse<Map<String, Object>> systemStatus(HttpServletRequest request) {
        requireAdmin(request);
        return ApiResponse.success(system.status());
    }

    @GetMapping("/ai/quota")
    public ApiResponse<Map<String, Object>> quota(HttpServletRequest request) {
        admin.requireSuperAdmin(requireAdmin(request));
        return ApiResponse.success(quota.settings());
    }

    @GetMapping("/ai/quota/usage")
    public ApiResponse<Map<String, Object>> usage(HttpServletRequest request, @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size) {
        admin.requireSuperAdmin(requireAdmin(request));
        return ApiResponse.success(quota.usage(page, size));
    }

    @PutMapping("/ai/quota")
    public ApiResponse<Map<String, Object>> updateQuota(HttpServletRequest request, @RequestBody Map<String, Object> body) {
        return ApiResponse.success(quota.update(requireAdmin(request), body, request.getRemoteAddr(), request.getHeader("User-Agent")));
    }

    @GetMapping("/ai/quota-alerts")
    public ApiResponse<Map<String, Object>> quotaAlerts(HttpServletRequest request,
            @RequestParam(required = false) String dateFrom, @RequestParam(required = false) String dateTo,
            @RequestParam(defaultValue = "1") int page, @RequestParam(defaultValue = "20") int size) {
        requireAdmin(request);
        return ApiResponse.success(analytics.quotaAlerts(AnalyticsRange.parse(dateFrom, dateTo), page, size));
    }

    @GetMapping("/security/login-anomalies")
    public ApiResponse<Map<String, Object>> anomalies(HttpServletRequest request,
            @RequestParam(required = false) String dateFrom, @RequestParam(required = false) String dateTo,
            @RequestParam(defaultValue = "1") int page, @RequestParam(defaultValue = "20") int size,
            @RequestParam(required = false) Long userId) {
        requireAdmin(request);
        return ApiResponse.success(analytics.anomalies(AnalyticsRange.parse(dateFrom, dateTo), page, size, userId));
    }

    @GetMapping("/security/register-analysis")
    public ApiResponse<Map<String, Object>> registrations(HttpServletRequest request,
            @RequestParam(required = false) String dateFrom, @RequestParam(required = false) String dateTo,
            @RequestParam(defaultValue = "1") int page, @RequestParam(defaultValue = "20") int size) {
        requireAdmin(request);
        return ApiResponse.success(analytics.registrations(AnalyticsRange.parse(dateFrom, dateTo), page, size));
    }

    @PutMapping("/users/{id}/ban")
    public ApiResponse<Map<String, Object>> ban(HttpServletRequest request, @PathVariable long id, @RequestBody(required = false) Map<String, Object> body) {
        return setStatus(request, id, "disabled", body);
    }

    @PutMapping("/users/{id}/unban")
    public ApiResponse<Map<String, Object>> unban(HttpServletRequest request, @PathVariable long id, @RequestBody(required = false) Map<String, Object> body) {
        return setStatus(request, id, "active", body);
    }

    private ApiResponse<Map<String, Object>> setStatus(HttpServletRequest request, long id, String status, Map<String, Object> body) {
        return ApiResponse.success(admin.adminSetUserStatus(requireAdmin(request), id, status,
                request.getRemoteAddr(), request.getHeader("User-Agent"), body == null ? "" : String.valueOf(body.getOrDefault("reason", ""))));
    }

    private long requireAdmin(HttpServletRequest request) { return auth.requireAdmin(request.getHeader("Authorization")); }
}
