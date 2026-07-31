package com.dayliane.reminder;

import com.dayliane.auth.AuthService;
import com.dayliane.common.ApiResponse;
import com.dayliane.common.BusinessException;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

@RestController
@RequestMapping("/api/v1")
public class ReminderController {
    private final ReminderService reminderService;
    private final AuthService authService;
    private final String internalScanToken;

    public ReminderController(ReminderService reminderService, AuthService authService,
                              @Value("${app.internal.reminder-scan-token:}") String internalScanToken) {
        this.reminderService = reminderService;
        this.authService = authService;
        this.internalScanToken = internalScanToken;
    }

    @GetMapping("/reminders/my")
    public ApiResponse<Map<String, Object>> my(HttpServletRequest request,
                                                @RequestParam(defaultValue = "1") int page,
                                                @RequestParam(defaultValue = "20") int size,
                                                @RequestParam(required = false) String status,
                                                @RequestParam(required = false) String targetType) {
        long userId = authService.requireUser(request.getHeader("Authorization"));
        return ApiResponse.success(reminderService.listMyReminders(userId, page, size, status, targetType));
    }

    @PostMapping("/internal/reminders/scan")
    public ApiResponse<Map<String, Object>> scan(HttpServletRequest request) {
        String supplied = request.getHeader("X-Internal-Token");
        if (internalScanToken.isBlank() || supplied == null || !MessageDigest.isEqual(
                internalScanToken.getBytes(StandardCharsets.UTF_8), supplied.getBytes(StandardCharsets.UTF_8))) {
            throw new BusinessException(401, "invalid internal token");
        }
        return ApiResponse.success(Map.of("sentCount", reminderService.scanReminders()));
    }
}
