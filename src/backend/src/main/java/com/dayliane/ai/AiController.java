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
        return ApiResponse.success(aiService.parseSchedule(userId, text(req), recordUsage(req)));
    }

    @PostMapping("/team-tasks/breakdown")
    public ApiResponse<Map<String, Object>> breakdownTeamTask(HttpServletRequest request, @RequestBody Map<String, Object> req) {
        long userId = authService.requireUser(request.getHeader("Authorization"));
        return ApiResponse.success(aiService.breakdownTeamTask(userId, text(req), recordUsage(req)));
    }

    @PostMapping("/home/daily-plan")
    public ApiResponse<Map<String, Object>> dailyPlan(HttpServletRequest request, @RequestBody(required = false) Map<String, Object> req) {
        long userId = authService.requireUser(request.getHeader("Authorization"));
        return ApiResponse.success(aiService.dailyPlan(userId, req == null || recordUsage(req)));
    }

    @PostMapping("/text/optimize-task-description")
    public ApiResponse<Map<String, Object>> optimizeTaskDescription(HttpServletRequest request, @RequestBody Map<String, Object> req) {
        long userId = authService.requireUser(request.getHeader("Authorization"));
        return ApiResponse.success(aiService.optimizeTaskDescription(userId, text(req), recordUsage(req)));
    }

    private static String text(Map<String, Object> req) {
        for (String key : new String[]{"text", "content", "description"}) {
            Object value = req.get(key);
            if (value != null && !String.valueOf(value).isBlank()) return String.valueOf(value);
        }
        return "";
    }

    private static boolean recordUsage(Map<String, Object> req) {
        Object value = req.get("recordUsage");
        return value == null || !(value instanceof Boolean b ? !b : "false".equalsIgnoreCase(String.valueOf(value)));
    }
}
