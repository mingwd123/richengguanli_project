package com.dayliane.notification;

import com.dayliane.auth.AuthService;
import com.dayliane.common.ApiResponse;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/api/v1/notifications")
public class NotificationController {
    private final NotificationService notificationService;
    private final AuthService authService;

    public NotificationController(NotificationService notificationService, AuthService authService) {
        this.notificationService = notificationService;
        this.authService = authService;
    }

    @GetMapping
    public ApiResponse<Map<String, Object>> list(HttpServletRequest request,
                                                  @RequestParam(defaultValue = "1") int page,
                                                  @RequestParam(defaultValue = "20") int size,
                                                  @RequestParam(name = "is_read", required = false) Boolean isRead) {
        long userId = authService.requireUser(request.getHeader("Authorization"));
        return ApiResponse.success(notificationService.listNotifications(userId, page, size, isRead));
    }

    @GetMapping("/unread-count")
    public ApiResponse<Map<String, Object>> unreadCount(HttpServletRequest request) {
        long userId = authService.requireUser(request.getHeader("Authorization"));
        return ApiResponse.success(Map.of("count", notificationService.unreadCount(userId)));
    }

    @PutMapping("/{id}/read")
    public ApiResponse<Map<String, Object>> read(HttpServletRequest request, @PathVariable long id) {
        long userId = authService.requireUser(request.getHeader("Authorization"));
        notificationService.readNotification(id, userId);
        return ApiResponse.success(Map.of("ok", true));
    }

    @PutMapping("/read-all")
    public ApiResponse<Map<String, Object>> readAll(HttpServletRequest request) {
        long userId = authService.requireUser(request.getHeader("Authorization"));
        notificationService.readAll(userId);
        return ApiResponse.success(Map.of("ok", true));
    }
}
