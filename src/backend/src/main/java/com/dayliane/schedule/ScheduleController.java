package com.dayliane.schedule;

import com.dayliane.auth.AuthService;
import com.dayliane.common.ApiResponse;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/api/v1/schedules")
public class ScheduleController {
    private final ScheduleService scheduleService;
    private final AuthService authService;

    public ScheduleController(ScheduleService scheduleService, AuthService authService) {
        this.scheduleService = scheduleService;
        this.authService = authService;
    }

    @PostMapping
    public ApiResponse<Map<String, Object>> create(HttpServletRequest request, @RequestBody Map<String, Object> req) {
        long userId = authService.requireUser(request.getHeader("Authorization"));
        return ApiResponse.success(scheduleService.createSchedule(userId, req));
    }

    @GetMapping
    public ApiResponse<Map<String, Object>> list(HttpServletRequest request,
                                                  @RequestParam(defaultValue = "1") int page,
                                                  @RequestParam(defaultValue = "20") int size,
                                                  @RequestParam(required = false) String status,
                                                  @RequestParam(name = "group_name", required = false) String groupName,
                                                  @RequestParam(required = false) String keyword,
                                                  @RequestParam(required = false) String dateFrom,
                                                  @RequestParam(required = false) String dateTo,
                                                  @RequestParam(defaultValue = "manual") String sort,
                                                  @RequestParam(defaultValue = "time") String viewMode,
                                                  @RequestParam(required = false) Integer urgencyLevel,
                                                  @RequestParam(required = false) Integer fatigueLevel,
                                                  @RequestParam(defaultValue = "false") boolean quickScope) {
        long userId = authService.requireUser(request.getHeader("Authorization"));
        return ApiResponse.success(scheduleService.listSchedules(userId, page, size, status, groupName, keyword, dateFrom, dateTo, sort, viewMode, urgencyLevel, fatigueLevel, quickScope));
    }

    @GetMapping("/{id}")
    public ApiResponse<Map<String, Object>> detail(HttpServletRequest request, @PathVariable long id) {
        long userId = authService.requireUser(request.getHeader("Authorization"));
        return ApiResponse.success(scheduleService.requireSchedule(id, userId));
    }

    @PutMapping("/sort")
    public ApiResponse<Map<String, Object>> sort(HttpServletRequest request, @RequestBody Map<String, Object> req) {
        long userId = authService.requireUser(request.getHeader("Authorization"));
        return ApiResponse.success(scheduleService.sortSchedules(userId, req));
    }

    @PutMapping("/completed/sort")
    public ApiResponse<Map<String, Object>> sortCompleted(HttpServletRequest request, @RequestBody Map<String, Object> req) {
        long userId = authService.requireUser(request.getHeader("Authorization"));
        return ApiResponse.success(scheduleService.sortCompletedSchedules(userId, req));
    }

    @PutMapping("/{id}/move-group")
    public ApiResponse<Map<String, Object>> moveGroup(HttpServletRequest request, @PathVariable long id, @RequestBody Map<String, Object> req) {
        long userId = authService.requireUser(request.getHeader("Authorization"));
        return ApiResponse.success(scheduleService.moveScheduleGroup(id, userId, req));
    }

    @PutMapping("/{id}")
    public ApiResponse<Map<String, Object>> update(HttpServletRequest request, @PathVariable long id, @RequestBody Map<String, Object> req) {
        long userId = authService.requireUser(request.getHeader("Authorization"));
        return ApiResponse.success(scheduleService.updateSchedule(id, userId, req));
    }

    @DeleteMapping("/{id}")
    public ApiResponse<Map<String, Object>> delete(HttpServletRequest request, @PathVariable long id) {
        long userId = authService.requireUser(request.getHeader("Authorization"));
        scheduleService.deleteSchedule(id, userId);
        return ApiResponse.success(Map.of("ok", true));
    }

    @PutMapping("/{id}/complete")
    public ApiResponse<Map<String, Object>> complete(HttpServletRequest request, @PathVariable long id) { return status(request, id, "completed"); }
    @PutMapping("/{id}/uncomplete")
    public ApiResponse<Map<String, Object>> uncomplete(HttpServletRequest request, @PathVariable long id) { return status(request, id, "pending"); }
    @PutMapping("/{id}/cancel")
    public ApiResponse<Map<String, Object>> cancel(HttpServletRequest request, @PathVariable long id) { return status(request, id, "cancelled"); }
    @PutMapping("/{id}/restore")
    public ApiResponse<Map<String, Object>> restore(HttpServletRequest request, @PathVariable long id) { return status(request, id, "pending"); }

    @GetMapping("/calendar")
    public ApiResponse<Map<String, Object>> calendar(HttpServletRequest request, @RequestParam int year, @RequestParam int month) {
        long userId = authService.requireUser(request.getHeader("Authorization"));
        return ApiResponse.success(scheduleService.calendar(userId, year, month));
    }

    @GetMapping("/ical")
    public ResponseEntity<String> ical(@RequestParam String token) {
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType("text/calendar;charset=UTF-8"))
                .body(scheduleService.calendarIcs(token));
    }

    @GetMapping("/subscribe-token")
    public ApiResponse<Map<String, Object>> subscribeToken(HttpServletRequest request) {
        long userId = authService.requireUser(request.getHeader("Authorization"));
        return ApiResponse.success(scheduleService.subscribeToken(userId));
    }

    @PostMapping("/subscribe-token")
    public ApiResponse<Map<String, Object>> resetSubscribeToken(HttpServletRequest request) {
        long userId = authService.requireUser(request.getHeader("Authorization"));
        return ApiResponse.success(scheduleService.resetSubscribeToken(userId));
    }

    @PostMapping("/repeat")
    public ApiResponse<Map<String, Object>> materializeRepeat(HttpServletRequest request, @RequestBody(required = false) Map<String, Object> req) {
        long userId = authService.requireUser(request.getHeader("Authorization"));
        return ApiResponse.success(scheduleService.materializeRepeat(userId, req));
    }

    @PutMapping("/{id}/occurrence")
    public ApiResponse<Map<String, Object>> editOccurrence(HttpServletRequest request, @PathVariable long id, @RequestBody Map<String, Object> req) {
        long userId = authService.requireUser(request.getHeader("Authorization"));
        return ApiResponse.success(scheduleService.editOccurrence(id, userId, req));
    }

    @PutMapping("/series/{seriesId}")
    public ApiResponse<Map<String, Object>> updateSeries(HttpServletRequest request, @PathVariable String seriesId, @RequestBody Map<String, Object> req) {
        long userId = authService.requireUser(request.getHeader("Authorization"));
        return ApiResponse.success(scheduleService.updateSeries(userId, seriesId, req));
    }

    @DeleteMapping("/series/{seriesId}")
    public ApiResponse<Map<String, Object>> deleteSeries(HttpServletRequest request, @PathVariable String seriesId, @RequestBody(required = false) Map<String, Object> req) {
        long userId = authService.requireUser(request.getHeader("Authorization"));
        return ApiResponse.success(scheduleService.deleteSeries(userId, seriesId, req == null ? Map.of() : req));
    }

    private ApiResponse<Map<String, Object>> status(HttpServletRequest request, long id, String status) {
        long userId = authService.requireUser(request.getHeader("Authorization"));
        return ApiResponse.success(scheduleService.setScheduleStatus(id, userId, status));
    }

}
