package com.dayliane.auth;

import com.dayliane.common.ApiResponse;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/api/v1/auth")
public class AuthController {
    private final AuthService authService;
    private final boolean trustForwardedHeaders;

    public AuthController(AuthService authService,
                          @Value("${app.http.trust-forwarded-headers:false}") boolean trustForwardedHeaders) {
        this.authService = authService;
        this.trustForwardedHeaders = trustForwardedHeaders;
    }

    @PostMapping("/register")
    public ApiResponse<Map<String, Object>> register(@RequestBody Map<String, Object> req) {
        return ApiResponse.success(authService.registerWithTokens(
                text(req, "email"),
                text(req, "code"),
                text(req, "password"),
                nullableText(req, "phone"),
                textOr(req, "nickname", "User"),
                textOr(req, "timezone", "Asia/Shanghai")));
    }

    @PostMapping("/login")
    public ApiResponse<Map<String, Object>> login(HttpServletRequest request, @RequestBody Map<String, Object> req) {
        String ip = clientIp(request);
        String account = text(req, "account");
        if (account.isBlank()) account = text(req, "phone");
        return ApiResponse.success(authService.login(account, text(req, "password"), ip));
    }

    @PostMapping("/email-code")
    public ApiResponse<Map<String, Object>> emailCode(HttpServletRequest request,
                                                       @RequestBody Map<String, Object> req) {
        int countdown = authService.sendEmailCode(
                request.getHeader("Authorization"),
                text(req, "email"),
                text(req, "purpose"),
                text(req, "currentPassword"),
                clientIp(request),
                request.getHeader("User-Agent"));
        return ApiResponse.success(Map.of("countdown", countdown));
    }

    @PostMapping("/reset-password")
    public ApiResponse<Map<String, Object>> resetPassword(@RequestBody Map<String, Object> req) {
        authService.resetPassword(text(req, "email"), text(req, "code"), text(req, "newPassword"));
        return ApiResponse.success(Map.of("ok", true));
    }

    @PostMapping("/refresh-token")
    public ApiResponse<Map<String, Object>> refresh(@RequestBody Map<String, Object> req) {
        return ApiResponse.success(authService.refreshToken(text(req, "refreshToken")));
    }

    @PostMapping("/logout")
    public ApiResponse<Map<String, Object>> logout(HttpServletRequest request,
                                                    @RequestBody(required = false) Map<String, Object> req) {
        authService.logout(request.getHeader("Authorization"), req == null ? null : text(req, "refreshToken"));
        return ApiResponse.success(Map.of("ok", true));
    }

    private static String text(Map<String, Object> req, String key) { return String.valueOf(req.getOrDefault(key, "")); }
    private static String textOr(Map<String, Object> req, String key, String fallback) { String v = text(req, key); return v.isBlank() ? fallback : v; }
    private static String nullableText(Map<String, Object> req, String key) {
        Object value = req.get(key);
        if (value == null) return null;
        String text = String.valueOf(value).trim();
        return text.isBlank() ? null : text;
    }
    private String clientIp(HttpServletRequest request) {
        String forwarded = trustForwardedHeaders ? request.getHeader("X-Forwarded-For") : null;
        if (forwarded != null && !forwarded.isBlank()) {
            String client = forwarded.split(",")[0].trim();
            if (!client.isBlank() && client.length() <= 45) return client;
        }
        return request.getRemoteAddr();
    }
}
