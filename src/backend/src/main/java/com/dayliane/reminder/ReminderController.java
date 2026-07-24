package com.dayliane.reminder;

import com.dayliane.common.ApiResponse;
import com.dayliane.common.DbStore;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
@RequestMapping("/api/v1/internal/reminders")
public class ReminderController {
    private final DbStore store;

    public ReminderController(DbStore store) {
        this.store = store;
    }

    @PostMapping("/scan")
    public ApiResponse<Map<String, Object>> scan() {
        return ApiResponse.success(Map.of("sentCount", store.scanReminders()));
    }
}
