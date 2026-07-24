package com.dayliane.notification;

import com.dayliane.common.ApiResponse;
import com.dayliane.common.DbStore;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/api/v1/notifications")
public class NotificationController {
    private final DbStore store;

    public NotificationController(DbStore store) {
        this.store = store;
    }

    @GetMapping
    public ApiResponse<Map<String, Object>> list(HttpServletRequest request,
                                                  @RequestParam(defaultValue = "1") int page,
                                                  @RequestParam(defaultValue = "20") int size,
                                                  @RequestParam(name = "is_read", required = false) Boolean isRead) {
        long userId = store.requireUser(request.getHeader("Authorization"));
        return ApiResponse.success(store.listNotifications(userId, page, size, isRead));
    }

    @GetMapping("/unread-count")
    public ApiResponse<Map<String, Object>> unreadCount(HttpServletRequest request) {
        long userId = store.requireUser(request.getHeader("Authorization"));
        return ApiResponse.success(Map.of("count", store.unreadCount(userId)));
    }

    @PutMapping("/{id}/read")
    public ApiResponse<Map<String, Object>> read(HttpServletRequest request, @PathVariable long id) {
        long userId = store.requireUser(request.getHeader("Authorization"));
        store.readNotification(id, userId);
        return ApiResponse.success(Map.of("ok", true));
    }

    @PutMapping("/read-all")
    public ApiResponse<Map<String, Object>> readAll(HttpServletRequest request) {
        long userId = store.requireUser(request.getHeader("Authorization"));
        store.readAll(userId);
        return ApiResponse.success(Map.of("ok", true));
    }
}
