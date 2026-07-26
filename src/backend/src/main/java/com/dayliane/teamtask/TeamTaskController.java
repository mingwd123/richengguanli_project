package com.dayliane.teamtask;

import com.dayliane.auth.AuthService;
import com.dayliane.common.ApiResponse;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/v1")
public class TeamTaskController {
    private final TeamTaskService teamTaskService;
    private final AuthService authService;

    public TeamTaskController(TeamTaskService teamTaskService, AuthService authService) {
        this.teamTaskService = teamTaskService;
        this.authService = authService;
    }

    @PostMapping("/team-tasks")
    public ApiResponse<Map<String, Object>> create(HttpServletRequest request, @RequestBody Map<String, Object> req) {
        long userId = authService.requireUser(request.getHeader("Authorization"));
        return ApiResponse.success(teamTaskService.createTeamTask(userId, req));
    }

    @GetMapping("/team-tasks/my")
    public ApiResponse<Map<String, Object>> my(HttpServletRequest request,
                                                @RequestParam(defaultValue = "1") int page,
                                                @RequestParam(defaultValue = "20") int size,
                                                @RequestParam(required = false) String status) {
        long userId = authService.requireUser(request.getHeader("Authorization"));
        return ApiResponse.success(teamTaskService.listMyTeamTasks(userId, page, size, status));
    }

    @GetMapping("/teams/{teamId}/tasks")
    public ApiResponse<Map<String, Object>> teamTasks(HttpServletRequest request, @PathVariable long teamId,
                                                       @RequestParam(defaultValue = "1") int page,
                                                       @RequestParam(defaultValue = "20") int size,
                                                       @RequestParam(required = false) String status) {
        long userId = authService.requireUser(request.getHeader("Authorization"));
        return ApiResponse.success(teamTaskService.listTeamTasks(teamId, userId, page, size, status));
    }

    @GetMapping("/team-tasks/{id}")
    public ApiResponse<Map<String, Object>> detail(HttpServletRequest request, @PathVariable long id) {
        long userId = authService.requireUser(request.getHeader("Authorization"));
        return ApiResponse.success(teamTaskService.teamTaskDetail(id, userId));
    }

    @PutMapping("/team-tasks/{id}")
    public ApiResponse<Map<String, Object>> update(HttpServletRequest request, @PathVariable long id, @RequestBody Map<String, Object> req) {
        long userId = authService.requireUser(request.getHeader("Authorization"));
        return ApiResponse.success(teamTaskService.updateTeamTask(id, userId, req));
    }

    @PutMapping("/team-tasks/{id}/time")
    public ApiResponse<Map<String, Object>> time(HttpServletRequest request, @PathVariable long id, @RequestBody Map<String, Object> req) {
        long userId = authService.requireUser(request.getHeader("Authorization"));
        return ApiResponse.success(teamTaskService.updateTeamTaskTime(id, userId, req));
    }

    @DeleteMapping("/team-tasks/{id}")
    public ApiResponse<Map<String, Object>> delete(HttpServletRequest request, @PathVariable long id) {
        long userId = authService.requireUser(request.getHeader("Authorization"));
        teamTaskService.deleteTeamTask(id, userId);
        return ApiResponse.success(Map.of("ok", true));
    }

    @PostMapping("/team-tasks/{id}/accept")
    public ApiResponse<Map<String, Object>> accept(HttpServletRequest request, @PathVariable long id) {
        long userId = authService.requireUser(request.getHeader("Authorization"));
        return ApiResponse.success(teamTaskService.teamTaskAssigneeTransition(id, userId, "accepted", List.of("pending")));
    }

    @PostMapping("/team-tasks/{id}/reject")
    public ApiResponse<Map<String, Object>> reject(HttpServletRequest request, @PathVariable long id) {
        long userId = authService.requireUser(request.getHeader("Authorization"));
        return ApiResponse.success(teamTaskService.teamTaskAssigneeTransition(id, userId, "rejected", List.of("pending", "accepted")));
    }

    @PostMapping("/team-tasks/{id}/complete")
    public ApiResponse<Map<String, Object>> complete(HttpServletRequest request, @PathVariable long id) {
        long userId = authService.requireUser(request.getHeader("Authorization"));
        return ApiResponse.success(teamTaskService.teamTaskAssigneeTransition(id, userId, "completed", List.of("accepted")));
    }

    @PostMapping("/team-tasks/{id}/cancel")
    public ApiResponse<Map<String, Object>> cancel(HttpServletRequest request, @PathVariable long id) {
        long userId = authService.requireUser(request.getHeader("Authorization"));
        return ApiResponse.success(teamTaskService.cancelTeamTask(id, userId));
    }

    @PostMapping("/team-tasks/{id}/restore")
    public ApiResponse<Map<String, Object>> restore(HttpServletRequest request, @PathVariable long id) {
        long userId = authService.requireUser(request.getHeader("Authorization"));
        return ApiResponse.success(teamTaskService.restoreTeamTask(id, userId));
    }

    @PostMapping("/team-tasks/{id}/reassign")
    public ApiResponse<Map<String, Object>> reassign(HttpServletRequest request, @PathVariable long id, @RequestBody Map<String, Object> req) {
        long userId = authService.requireUser(request.getHeader("Authorization"));
        long originalUserId = ((Number) req.get("originalAssigneeUserId")).longValue();
        long newUserId = ((Number) req.get("newAssigneeUserId")).longValue();
        return ApiResponse.success(teamTaskService.reassignTeamTask(id, userId, originalUserId, newUserId));
    }

    @PutMapping("/team-tasks/{id}/assignees/{assigneeId}/status")
    public ApiResponse<Map<String, Object>> assigneeStatus(HttpServletRequest request, @PathVariable long id, @PathVariable long assigneeId, @RequestBody Map<String, Object> req) {
        long userId = authService.requireUser(request.getHeader("Authorization"));
        return ApiResponse.success(teamTaskService.correctTeamTaskAssigneeStatus(id, assigneeId, userId, String.valueOf(req.getOrDefault("status", "pending"))));
    }
}
