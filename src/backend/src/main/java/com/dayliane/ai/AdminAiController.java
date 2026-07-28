package com.dayliane.ai;

import com.dayliane.auth.AuthService;
import com.dayliane.common.ApiResponse;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.GetMapping;
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

    public AdminAiController(AiService aiService, AuthService authService) {
        this.aiService = aiService;
        this.authService = authService;
    }

    @GetMapping("/config")
    public ApiResponse<Map<String, Object>> config(HttpServletRequest request) {
        authService.requireAdmin(request.getHeader("Authorization"));
        return ApiResponse.success(aiService.configView());
    }

    @PutMapping("/config")
    public ApiResponse<Map<String, Object>> updateConfig(HttpServletRequest request, @RequestBody Map<String, Object> req) {
        long adminId = authService.requireAdmin(request.getHeader("Authorization"));
        return ApiResponse.success(aiService.updateConfig(adminId, req, clientIp(request), userAgent(request)));
    }

    @PutMapping("/enabled")
    public ApiResponse<Map<String, Object>> updateEnabled(HttpServletRequest request, @RequestBody Map<String, Object> req) {
        long adminId = authService.requireAdmin(request.getHeader("Authorization"));
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
        authService.requireAdmin(request.getHeader("Authorization"));
        return ApiResponse.success(aiService.test());
    }

    private static String userAgent(HttpServletRequest request) { return request.getHeader("User-Agent"); }
    private static String clientIp(HttpServletRequest request) {
        String forwarded = request.getHeader("X-Forwarded-For");
        return forwarded != null && !forwarded.isBlank() ? forwarded.split(",")[0].trim() : request.getRemoteAddr();
    }
}
