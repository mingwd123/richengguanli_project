package com.dayliane.user;

import com.dayliane.auth.AuthService;
import com.dayliane.common.ApiResponse;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/api/v1/user")
public class UserController {
    private final UserService userService;
    private final AuthService authService;

    public UserController(UserService userService, AuthService authService) {
        this.userService = userService;
        this.authService = authService;
    }

    @GetMapping("/profile")
    public ApiResponse<Map<String, Object>> profile(HttpServletRequest request) {
        long userId = authService.requireUser(request.getHeader("Authorization"));
        return ApiResponse.success(userService.userView(userId));
    }

    @PutMapping("/profile")
    public ApiResponse<Map<String, Object>> updateProfile(HttpServletRequest request, @RequestBody Map<String, Object> req) {
        long userId = authService.requireUser(request.getHeader("Authorization"));
        userService.updateUserProfile(userId, req);
        return ApiResponse.success(userService.userView(userId));
    }

    @PutMapping("/password")
    public ApiResponse<Map<String, Object>> password(HttpServletRequest request, @RequestBody Map<String, Object> req) {
        long userId = authService.requireUser(request.getHeader("Authorization"));
        userService.updatePassword(userId, String.valueOf(req.getOrDefault("oldPassword", "")), String.valueOf(req.getOrDefault("newPassword", "")));
        return ApiResponse.success(Map.of("ok", true));
    }

    @PutMapping("/email")
    public ApiResponse<Map<String, Object>> email(HttpServletRequest request, @RequestBody Map<String, Object> req) {
        long userId = authService.requireUser(request.getHeader("Authorization"));
        boolean reauthenticate = userService.updateEmail(
                userId,
                String.valueOf(req.getOrDefault("email", "")),
                String.valueOf(req.getOrDefault("code", "")),
                String.valueOf(req.getOrDefault("currentPassword", "")));
        return ApiResponse.success(Map.of("ok", true, "reauthenticate", reauthenticate));
    }

    @PutMapping("/timezone")
    public ApiResponse<Map<String, Object>> timezone(HttpServletRequest request, @RequestBody Map<String, Object> req) {
        long userId = authService.requireUser(request.getHeader("Authorization"));
        userService.updateTimezone(userId, String.valueOf(req.getOrDefault("timezone", "Asia/Shanghai")));
        return ApiResponse.success(userService.userView(userId));
    }
}
