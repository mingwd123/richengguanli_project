package com.dayliane.ai;

import com.dayliane.auth.AuthService;
import com.dayliane.common.ApiResponse;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
@RequestMapping("/api/v1/ai")
public class AiController {
    private final AiService aiService;
    private final AuthService authService;

    public AiController(AiService aiService, AuthService authService) {
        this.aiService = aiService;
        this.authService = authService;
    }

    @PostMapping("/schedules/parse")
    public ApiResponse<Map<String, Object>> parseSchedule(HttpServletRequest request, @RequestBody Map<String, Object> req) {
        long userId = authService.requireUser(request.getHeader("Authorization"));
        return ApiResponse.success(aiService.parseSchedule(userId, text(req), aiService.aiRecordEnabled(userId)));
    }

    @PostMapping("/schedules/arrange")
    public ApiResponse<Map<String, Object>> arrangeSchedules(HttpServletRequest request, @RequestBody(required = false) Map<String, Object> req) {
        long userId = authService.requireUser(request.getHeader("Authorization"));
        return ApiResponse.success(aiService.arrangeSchedules(userId, aiService.aiRecordEnabled(userId)));
    }

    @PostMapping("/team-tasks/breakdown")
    public ApiResponse<Map<String, Object>> breakdownTeamTask(HttpServletRequest request, @RequestBody Map<String, Object> req) {
        long userId = authService.requireUser(request.getHeader("Authorization"));
        return ApiResponse.success(aiService.breakdownTeamTask(userId, text(req), aiService.aiRecordEnabled(userId)));
    }

    @PostMapping("/home/daily-plan")
    public ApiResponse<Map<String, Object>> dailyPlan(HttpServletRequest request, @RequestBody(required = false) Map<String, Object> req) {
        long userId = authService.requireUser(request.getHeader("Authorization"));
        return ApiResponse.success(aiService.dailyPlan(userId, aiService.aiRecordEnabled(userId)));
    }

    @PostMapping("/text/optimize-task-description")
    public ApiResponse<Map<String, Object>> optimizeTaskDescription(HttpServletRequest request, @RequestBody Map<String, Object> req) {
        long userId = authService.requireUser(request.getHeader("Authorization"));
        return ApiResponse.success(aiService.optimizeTaskDescription(userId, text(req), aiService.aiRecordEnabled(userId)));
    }

    private static String text(Map<String, Object> req) {
        for (String key : new String[]{"text", "content", "description"}) {
            Object value = req.get(key);
            if (value != null && !String.valueOf(value).isBlank()) return String.valueOf(value);
        }
        return "";
    }

}
