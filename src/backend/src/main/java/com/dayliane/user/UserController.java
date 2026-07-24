package com.dayliane.user;

import com.dayliane.common.ApiResponse;
import com.dayliane.common.DbStore;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/api/v1/user")
public class UserController {
    private final DbStore store;

    public UserController(DbStore store) {
        this.store = store;
    }

    @GetMapping("/profile")
    public ApiResponse<Map<String, Object>> profile(HttpServletRequest request) {
        long userId = store.requireUser(request.getHeader("Authorization"));
        return ApiResponse.success(store.userView(userId));
    }

    @PutMapping("/profile")
    public ApiResponse<Map<String, Object>> updateProfile(HttpServletRequest request, @RequestBody Map<String, Object> req) {
        long userId = store.requireUser(request.getHeader("Authorization"));
        store.updateUserProfile(userId, req);
        return ApiResponse.success(store.userView(userId));
    }

    @PutMapping("/password")
    public ApiResponse<Map<String, Object>> password(HttpServletRequest request, @RequestBody Map<String, Object> req) {
        long userId = store.requireUser(request.getHeader("Authorization"));
        store.updatePassword(userId, String.valueOf(req.getOrDefault("oldPassword", "")), String.valueOf(req.getOrDefault("newPassword", "")));
        return ApiResponse.success(Map.of("ok", true));
    }

    @PutMapping("/timezone")
    public ApiResponse<Map<String, Object>> timezone(HttpServletRequest request, @RequestBody Map<String, Object> req) {
        long userId = store.requireUser(request.getHeader("Authorization"));
        store.updateTimezone(userId, String.valueOf(req.getOrDefault("timezone", "Asia/Shanghai")));
        return ApiResponse.success(store.userView(userId));
    }
}
