package com.dayliane.auth;

import com.dayliane.common.ApiResponse;
import com.dayliane.common.BusinessException;
import com.dayliane.common.DbStore;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/api/v1/auth")
public class AuthController {
    private final DbStore store;

    public AuthController(DbStore store) {
        this.store = store;
    }

    @PostMapping("/register")
    public ApiResponse<Map<String, Object>> register(@RequestBody Map<String, Object> req) {
        long id = store.register(text(req, "phone"), text(req, "password"), textOr(req, "nickname", "User"), textOr(req, "timezone", "Asia/Shanghai"));
        return ApiResponse.success(tokens(id));
    }

    @PostMapping("/login")
    public ApiResponse<Map<String, Object>> login(@RequestBody Map<String, Object> req) {
        long id = store.login(text(req, "phone"), text(req, "password"));
        return ApiResponse.success(tokens(id));
    }

    @PostMapping("/refresh-token")
    public ApiResponse<Map<String, Object>> refresh(@RequestBody Map<String, Object> req) {
        Long id = store.refreshUserId(text(req, "refreshToken"));
        if (id == null) throw new BusinessException(401, "refresh token is invalid");
        return ApiResponse.success(tokens(id));
    }

    @PostMapping("/logout")
    public ApiResponse<Map<String, Object>> logout(HttpServletRequest request) {
        store.revokeAccessToken(request.getHeader("Authorization"));
        return ApiResponse.success(Map.of("ok", true));
    }

    private Map<String, Object> tokens(long userId) {
        return Map.of("userId", userId, "accessToken", store.issueAccessToken(userId), "refreshToken", store.issueRefreshToken(userId), "expiresIn", 86400);
    }

    private static String text(Map<String, Object> req, String key) { return String.valueOf(req.getOrDefault(key, "")); }
    private static String textOr(Map<String, Object> req, String key, String fallback) { String v = text(req, key); return v.isBlank() ? fallback : v; }
}
