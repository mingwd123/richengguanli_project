package com.dayliane.fatigue;

import com.dayliane.auth.AuthService;
import com.dayliane.common.ApiResponse;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.nio.charset.StandardCharsets;
import java.util.Map;

@RestController
@RequestMapping("/api/v1/fatigue")
public class FatigueController {
    private final FatigueService fatigueService;
    private final AuthService authService;

    public FatigueController(FatigueService fatigueService, AuthService authService) {
        this.fatigueService = fatigueService;
        this.authService = authService;
    }

    @GetMapping("/profile")
    public ApiResponse<Map<String, Object>> profile(HttpServletRequest request) {
        return ApiResponse.success(fatigueService.profile(userId(request)));
    }

    @PutMapping("/preferences")
    public ApiResponse<Map<String, Object>> preferences(HttpServletRequest request, @RequestBody Map<String, Object> body) {
        return ApiResponse.success(fatigueService.updatePreferences(userId(request), body));
    }

    @PostMapping("/profile/reset")
    public ApiResponse<Map<String, Object>> reset(HttpServletRequest request) {
        return ApiResponse.success(fatigueService.resetProfile(userId(request)));
    }

    @GetMapping("/daily")
    public ApiResponse<Map<String, Object>> daily(HttpServletRequest request, @RequestParam(required = false) String date) {
        long userId = userId(request);
        return ApiResponse.success(date == null || date.isBlank()
                ? fatigueService.daily(userId)
                : fatigueService.daily(userId, date));
    }

    @GetMapping("/history")
    public ApiResponse<Map<String, Object>> history(HttpServletRequest request,
                                                    @RequestParam String dateFrom,
                                                    @RequestParam String dateTo) {
        return ApiResponse.success(fatigueService.history(userId(request), dateFrom, dateTo));
    }

    @GetMapping("/report")
    public ApiResponse<Map<String, Object>> report(HttpServletRequest request,
                                                   @RequestParam String period,
                                                   @RequestParam(required = false) String date) {
        return ApiResponse.success(fatigueService.report(userId(request), period, date));
    }

    @GetMapping("/survey/today")
    public ApiResponse<Map<String, Object>> surveyToday(HttpServletRequest request,
                                                        @RequestParam(required = false) String date) {
        return ApiResponse.success(fatigueService.surveyToday(userId(request), date));
    }

    @PostMapping("/surveys")
    public ApiResponse<Map<String, Object>> submitSurvey(HttpServletRequest request, @RequestBody Map<String, Object> body) {
        return ApiResponse.success(fatigueService.submitSurvey(userId(request), body));
    }

    @PutMapping("/surveys/{localDate}")
    public ApiResponse<Map<String, Object>> updateSurvey(HttpServletRequest request,
                                                         @PathVariable String localDate,
                                                         @RequestBody Map<String, Object> body) {
        Map<String, Object> payload = new java.util.LinkedHashMap<>(body);
        payload.put("localDate", localDate);
        return ApiResponse.success(fatigueService.submitSurvey(userId(request), payload));
    }

    @PostMapping("/preview")
    public ApiResponse<Map<String, Object>> preview(HttpServletRequest request, @RequestBody Map<String, Object> body) {
        return ApiResponse.success(fatigueService.preview(userId(request), body));
    }

    @PostMapping("/survey/snooze")
    public ApiResponse<Map<String, Object>> snoozeSurvey(HttpServletRequest request,
                                                         @RequestBody(required = false) Map<String, Object> body) {
        return ApiResponse.success(fatigueService.snoozeSurvey(userId(request), body == null ? Map.of() : body));
    }

    @PostMapping("/survey/skip")
    public ApiResponse<Map<String, Object>> skipSurvey(HttpServletRequest request,
                                                       @RequestBody(required = false) Map<String, Object> body) {
        return ApiResponse.success(fatigueService.skipSurvey(userId(request), body == null ? Map.of() : body));
    }

    @PostMapping("/alerts/suppress-today")
    public ApiResponse<Map<String, Object>> suppressAlertsToday(HttpServletRequest request,
                                                                @RequestBody(required = false) Map<String, Object> body) {
        return ApiResponse.success(fatigueService.suppressAlertsToday(userId(request), body == null ? Map.of() : body));
    }

    @GetMapping("/history/export")
    public ResponseEntity<byte[]> exportHistory(HttpServletRequest request,
                                                @RequestParam String dateFrom,
                                                @RequestParam String dateTo) {
        String csv = fatigueService.exportHistoryCsv(userId(request), dateFrom, dateTo);
        ContentDisposition disposition = ContentDisposition.attachment()
                .filename("fatigue-history-" + dateFrom + "-" + dateTo + ".csv", StandardCharsets.UTF_8)
                .build();
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, disposition.toString())
                .contentType(MediaType.parseMediaType("text/csv;charset=UTF-8"))
                .body(("\uFEFF" + csv).getBytes(StandardCharsets.UTF_8));
    }

    @DeleteMapping("/surveys/history")
    public ApiResponse<Map<String, Object>> deleteSurveyHistory(HttpServletRequest request) {
        return ApiResponse.success(fatigueService.deleteSurveyHistory(userId(request)));
    }

    private long userId(HttpServletRequest request) {
        return authService.requireUser(request.getHeader("Authorization"));
    }
}
