package com.dayliane.ai;

import com.dayliane.auth.AuthService;
import com.dayliane.admin.AdminService;
import com.dayliane.common.ApiResponse;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
@RequestMapping("/api/v1/admin/ai")
public class AdminAiController {
    private final AiService aiService;
    private final AuthService authService;
    private final AdminService adminService;

    public AdminAiController(AiService aiService, AuthService authService, AdminService adminService) {
        this.aiService = aiService;
        this.authService = authService;
        this.adminService = adminService;
    }

    @GetMapping("/config")
    public ApiResponse<Map<String, Object>> config(HttpServletRequest request) {
        requireSuperAdmin(request);
        return ApiResponse.success(aiService.configView());
    }

    @GetMapping("/keys")
    public ApiResponse<Map<String, Object>> keys(HttpServletRequest request) {
        requireSuperAdmin(request);
        return ApiResponse.success(aiService.keyPoolView());
    }

    @PostMapping("/keys")
    public ApiResponse<Map<String, Object>> createKey(HttpServletRequest request, @RequestBody Map<String, Object> req) {
        long adminId = requireSuperAdmin(request);
        return ApiResponse.success(aiService.createApiKey(adminId, req, clientIp(request), userAgent(request)));
    }

    @PutMapping("/keys/order")
    public ApiResponse<Map<String, Object>> reorderKeys(HttpServletRequest request, @RequestBody Map<String, Object> req) {
        long adminId = requireSuperAdmin(request);
        return ApiResponse.success(aiService.reorderApiKeys(adminId, req, clientIp(request), userAgent(request)));
    }

    @PutMapping("/keys/{keyId}")
    public ApiResponse<Map<String, Object>> updateKey(HttpServletRequest request, @PathVariable long keyId,
                                                       @RequestBody Map<String, Object> req) {
        long adminId = requireSuperAdmin(request);
        return ApiResponse.success(aiService.updateApiKey(adminId, keyId, req, clientIp(request), userAgent(request)));
    }

    @PutMapping("/keys/{keyId}/enabled")
    public ApiResponse<Map<String, Object>> updateKeyEnabled(HttpServletRequest request, @PathVariable long keyId,
                                                              @RequestBody Map<String, Object> req) {
        long adminId = requireSuperAdmin(request);
        Object value = req.get("enabled");
        if (value == null) throw new com.dayliane.common.BusinessException(400, "enabled is required");
        boolean enabled;
        if (value instanceof Boolean bool) enabled = bool;
        else if ("true".equalsIgnoreCase(String.valueOf(value)) || "false".equalsIgnoreCase(String.valueOf(value))) {
            enabled = Boolean.parseBoolean(String.valueOf(value));
        } else throw new com.dayliane.common.BusinessException(400, "enabled is invalid");
        return ApiResponse.success(aiService.updateApiKeyEnabled(adminId, keyId, enabled, clientIp(request), userAgent(request)));
    }

    @DeleteMapping("/keys/{keyId}")
    public ApiResponse<Map<String, Object>> deleteKey(HttpServletRequest request, @PathVariable long keyId) {
        long adminId = requireSuperAdmin(request);
        return ApiResponse.success(aiService.deleteApiKey(adminId, keyId, clientIp(request), userAgent(request)));
    }

    @PostMapping("/keys/{keyId}/test")
    public ApiResponse<Map<String, Object>> testKey(HttpServletRequest request, @PathVariable long keyId) {
        long adminId = requireSuperAdmin(request);
        return ApiResponse.success(aiService.testApiKey(adminId, keyId, clientIp(request), userAgent(request)));
    }

    @PutMapping("/config")
    public ApiResponse<Map<String, Object>> updateConfig(HttpServletRequest request, @RequestBody Map<String, Object> req) {
        long adminId = requireSuperAdmin(request);
        return ApiResponse.success(aiService.updateConfig(adminId, req, clientIp(request), userAgent(request)));
    }

    @PutMapping("/enabled")
    public ApiResponse<Map<String, Object>> updateEnabled(HttpServletRequest request, @RequestBody Map<String, Object> req) {
        long adminId = requireSuperAdmin(request);
        Object value = req.get("enabled");
        if (value == null) throw new com.dayliane.common.BusinessException(400, "enabled is required");
        boolean enabled = value instanceof Boolean b ? b : Boolean.parseBoolean(String.valueOf(value));
        return ApiResponse.success(aiService.updateEnabled(adminId, enabled, clientIp(request), userAgent(request)));
    }

    @GetMapping("/usage-logs")
    public ApiResponse<Map<String, Object>> usageLogs(HttpServletRequest request, @RequestParam(defaultValue = "1") int page,
                                                       @RequestParam(defaultValue = "20") int size, @RequestParam(required = false) String userId,
                                                       @RequestParam(required = false) String featureType, @RequestParam(required = false) String status,
                                                       @RequestParam(required = false) String dateFrom, @RequestParam(required = false) String dateTo) {
        authService.requireAdmin(request.getHeader("Authorization"));
        return ApiResponse.success(aiService.usageLogs(page, size, userId, featureType, status, dateFrom, dateTo));
    }

    @GetMapping("/usage-stats")
    public ApiResponse<Map<String, Object>> usageStats(HttpServletRequest request, @RequestParam(required = false) String dateFrom,
                                                        @RequestParam(required = false) String dateTo) {
        authService.requireAdmin(request.getHeader("Authorization"));
        return ApiResponse.success(aiService.usageStats(dateFrom, dateTo));
    }

    @PostMapping("/test")
    public ApiResponse<Map<String, Object>> test(HttpServletRequest request) {
        long adminId = requireSuperAdmin(request);
        return ApiResponse.success(aiService.test(adminId, clientIp(request), userAgent(request)));
    }

    private long requireSuperAdmin(HttpServletRequest request) {
        long adminId = authService.requireAdmin(request.getHeader("Authorization"));
        adminService.requireSuperAdmin(adminId);
        return adminId;
    }

    private static String userAgent(HttpServletRequest request) { return request.getHeader("User-Agent"); }
    private static String clientIp(HttpServletRequest request) {
        String forwarded = request.getHeader("X-Forwarded-For");
        return forwarded != null && !forwarded.isBlank() ? forwarded.split(",")[0].trim() : request.getRemoteAddr();
    }
}
