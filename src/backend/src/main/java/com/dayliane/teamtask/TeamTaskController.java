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
                                                @RequestParam(required = false) String status,
                                                @RequestParam(required = false) String keyword,
                                                @RequestParam(required = false) String dateFrom,
                                                @RequestParam(required = false) String dateTo,
                                                @RequestParam(defaultValue = "manual") String sort) {
        long userId = authService.requireUser(request.getHeader("Authorization"));
        return ApiResponse.success(teamTaskService.listMyTeamTasks(userId, page, size, status, keyword, dateFrom, dateTo, sort));
    }

    @GetMapping("/team-tasks/created")
    public ApiResponse<Map<String, Object>> created(HttpServletRequest request,
                                                     @RequestParam(defaultValue = "1") int page,
                                                     @RequestParam(defaultValue = "20") int size,
                                                     @RequestParam(required = false) String status,
                                                     @RequestParam(required = false) String keyword,
                                                     @RequestParam(required = false) String dateFrom,
                                                     @RequestParam(required = false) String dateTo,
                                                     @RequestParam(defaultValue = "manual") String sort) {
        long userId = authService.requireUser(request.getHeader("Authorization"));
        return ApiResponse.success(teamTaskService.listCreatedTeamTasks(userId, page, size, status, keyword, dateFrom, dateTo, sort));
    }

    @GetMapping("/teams/{teamId}/tasks")
    public ApiResponse<Map<String, Object>> teamTasks(HttpServletRequest request, @PathVariable long teamId,
                                                       @RequestParam(defaultValue = "1") int page,
                                                       @RequestParam(defaultValue = "20") int size,
                                                       @RequestParam(required = false) String status,
                                                       @RequestParam(required = false) String keyword,
                                                       @RequestParam(required = false) String dateFrom,
                                                       @RequestParam(required = false) String dateTo,
                                                       @RequestParam(defaultValue = "manual") String sort) {
        long userId = authService.requireUser(request.getHeader("Authorization"));
        return ApiResponse.success(teamTaskService.listTeamTasks(teamId, userId, page, size, status, keyword, dateFrom, dateTo, sort));
    }

    @GetMapping("/teams/{teamId}/task-groups")
    public ApiResponse<Map<String, Object>> taskGroups(HttpServletRequest request, @PathVariable long teamId) {
        long userId = authService.requireUser(request.getHeader("Authorization"));
        return ApiResponse.success(teamTaskService.listTeamTaskGroups(teamId, userId));
    }

    @PostMapping("/teams/{teamId}/task-groups")
    public ApiResponse<Map<String, Object>> createTaskGroup(HttpServletRequest request, @PathVariable long teamId, @RequestBody Map<String, Object> req) {
        long userId = authService.requireUser(request.getHeader("Authorization"));
        return ApiResponse.success(teamTaskService.createTeamTaskGroup(teamId, userId, req));
    }

    @PutMapping("/teams/{teamId}/task-groups/{groupId}")
    public ApiResponse<Map<String, Object>> updateTaskGroup(HttpServletRequest request, @PathVariable long teamId, @PathVariable long groupId, @RequestBody Map<String, Object> req) {
        long userId = authService.requireUser(request.getHeader("Authorization"));
        return ApiResponse.success(teamTaskService.updateTeamTaskGroup(teamId, groupId, userId, req));
    }

    @DeleteMapping("/teams/{teamId}/task-groups/{groupId}")
    public ApiResponse<Map<String, Object>> deleteTaskGroup(HttpServletRequest request, @PathVariable long teamId, @PathVariable long groupId, @RequestBody(required = false) Map<String, Object> req) {
        long userId = authService.requireUser(request.getHeader("Authorization"));
        teamTaskService.deleteTeamTaskGroup(teamId, groupId, userId, req);
        return ApiResponse.success(Map.of("ok", true));
    }

    @PutMapping("/teams/{teamId}/task-groups/sort")
    public ApiResponse<Map<String, Object>> sortTaskGroups(HttpServletRequest request, @PathVariable long teamId, @RequestBody Map<String, Object> req) {
        long userId = authService.requireUser(request.getHeader("Authorization"));
        return ApiResponse.success(teamTaskService.sortTeamTaskGroups(teamId, userId, req));
    }

    @PutMapping("/teams/{teamId}/tasks/sort")
    public ApiResponse<Map<String, Object>> sortTeamTasks(HttpServletRequest request, @PathVariable long teamId, @RequestBody Map<String, Object> req) {
        long userId = authService.requireUser(request.getHeader("Authorization"));
        return ApiResponse.success(teamTaskService.sortTeamTasks(teamId, userId, req));
    }

    @PutMapping("/teams/{teamId}/tasks/completed/sort")
    public ApiResponse<Map<String, Object>> sortCompletedTeamTasks(HttpServletRequest request, @PathVariable long teamId, @RequestBody Map<String, Object> req) {
        long userId = authService.requireUser(request.getHeader("Authorization"));
        return ApiResponse.success(teamTaskService.sortCompletedTeamTasks(teamId, userId, req));
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

    @PutMapping("/team-tasks/{id}/move-group")
    public ApiResponse<Map<String, Object>> moveGroup(HttpServletRequest request, @PathVariable long id, @RequestBody Map<String, Object> req) {
        long userId = authService.requireUser(request.getHeader("Authorization"));
        return ApiResponse.success(teamTaskService.moveTeamTaskGroup(id, userId, req));
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

    @PostMapping("/team-tasks/{id}/approve")
    public ApiResponse<Map<String, Object>> approve(HttpServletRequest request, @PathVariable long id) {
        long userId = authService.requireUser(request.getHeader("Authorization"));
        return ApiResponse.success(teamTaskService.approveTeamTask(id, userId));
    }

    @PostMapping("/team-tasks/{id}/reject-approval")
    public ApiResponse<Map<String, Object>> rejectApproval(HttpServletRequest request, @PathVariable long id) {
        long userId = authService.requireUser(request.getHeader("Authorization"));
        return ApiResponse.success(teamTaskService.rejectTeamTaskApproval(id, userId));
    }

    @PostMapping("/team-tasks/{id}/resubmit-approval")
    public ApiResponse<Map<String, Object>> resubmitApproval(HttpServletRequest request, @PathVariable long id) {
        long userId = authService.requireUser(request.getHeader("Authorization"));
        return ApiResponse.success(teamTaskService.resubmitTeamTaskApproval(id, userId));
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
