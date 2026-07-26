package com.dayliane.auth;

import com.dayliane.common.ApiResponse;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/api/v1/auth")
public class AuthController {
    private final AuthService authService;

    public AuthController(AuthService authService) {
        this.authService = authService;
    }

    @PostMapping("/register")
    public ApiResponse<Map<String, Object>> register(@RequestBody Map<String, Object> req) {
        long id = authService.register(text(req, "phone"), text(req, "password"), textOr(req, "nickname", "User"), textOr(req, "timezone", "Asia/Shanghai"));
        return ApiResponse.success(tokens(id));
    }

    @PostMapping("/login")
    public ApiResponse<Map<String, Object>> login(HttpServletRequest request, @RequestBody Map<String, Object> req) {
        String ip = clientIp(request);
        return ApiResponse.success(authService.login(text(req, "phone"), text(req, "password"), ip));
    }

    @PostMapping("/refresh-token")
    public ApiResponse<Map<String, Object>> refresh(@RequestBody Map<String, Object> req) {
        return ApiResponse.success(authService.refreshToken(text(req, "refreshToken")));
    }

    @PostMapping("/logout")
    public ApiResponse<Map<String, Object>> logout(HttpServletRequest request) {
        authService.logout(request.getHeader("Authorization"));
        return ApiResponse.success(Map.of("ok", true));
    }

    private Map<String, Object> tokens(long userId) {
        return Map.of("userId", userId, "accessToken", authService.issueAccessToken(userId), "refreshToken", authService.issueRefreshToken(userId), "expiresIn", 86400);
    }

    private static String text(Map<String, Object> req, String key) { return String.valueOf(req.getOrDefault(key, "")); }
    private static String textOr(Map<String, Object> req, String key, String fallback) { String v = text(req, key); return v.isBlank() ? fallback : v; }
    private static String clientIp(HttpServletRequest request) {
        String forwarded = request.getHeader("X-Forwarded-For");
        if (forwarded != null && !forwarded.isBlank()) return forwarded.split(",")[0].trim();
        return request.getRemoteAddr();
    }
}
