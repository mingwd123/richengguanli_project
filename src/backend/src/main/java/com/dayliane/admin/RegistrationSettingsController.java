package com.dayliane.admin;

import com.dayliane.auth.AuthService;
import com.dayliane.auth.RegistrationSettingsService;
import com.dayliane.common.ApiResponse;
import com.dayliane.common.BusinessException;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
@RequestMapping("/api/v1/admin/registration-settings")
public class RegistrationSettingsController {
    private final AuthService authService;
    private final RegistrationSettingsService registrationSettingsService;
    private final AdminRegistrationSettingsService adminRegistrationSettingsService;

    public RegistrationSettingsController(AuthService authService,
                                          RegistrationSettingsService registrationSettingsService,
                                          AdminRegistrationSettingsService adminRegistrationSettingsService) {
        this.authService = authService;
        this.registrationSettingsService = registrationSettingsService;
        this.adminRegistrationSettingsService = adminRegistrationSettingsService;
    }

    @GetMapping
    public ApiResponse<Map<String, Object>> get(HttpServletRequest request) {
        authService.requireAdmin(request.getHeader("Authorization"));
        return ApiResponse.success(registrationSettingsService.current());
    }

    @PutMapping
    public ApiResponse<Map<String, Object>> update(HttpServletRequest request,
                                                   @RequestBody(required = false) UpdateRequest body) {
        long adminId = authService.requireAdmin(request.getHeader("Authorization"));
        if (body == null || body.registrationEnabled() == null) {
            throw new BusinessException(400, "registrationEnabled is required");
        }
        return ApiResponse.success(adminRegistrationSettingsService.update(
                adminId, body.registrationEnabled(), clientIp(request), request.getHeader("User-Agent")));
    }

    private static String clientIp(HttpServletRequest request) {
        String forwarded = request.getHeader("X-Forwarded-For");
        if (forwarded != null && !forwarded.isBlank()) return forwarded.split(",")[0].trim();
        return request.getRemoteAddr();
    }

    private record UpdateRequest(Boolean registrationEnabled) {}
}
