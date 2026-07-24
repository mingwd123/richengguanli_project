package com.dayliane.team;

import com.dayliane.common.ApiResponse;
import com.dayliane.common.DbStore;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/api/v1/teams")
public class TeamController {
    private final DbStore store;

    public TeamController(DbStore store) {
        this.store = store;
    }

    @PostMapping
    public ApiResponse<Map<String, Object>> create(HttpServletRequest request, @RequestBody Map<String, Object> req) {
        long userId = store.requireUser(request.getHeader("Authorization"));
        return ApiResponse.success(store.createTeam(userId, String.valueOf(req.getOrDefault("name", ""))));
    }

    @GetMapping
    public ApiResponse<Map<String, Object>> list(HttpServletRequest request,
                                                  @RequestParam(defaultValue = "1") int page,
                                                  @RequestParam(defaultValue = "20") int size) {
        long userId = store.requireUser(request.getHeader("Authorization"));
        return ApiResponse.success(store.listTeams(userId, page, size));
    }

    @GetMapping("/{id}")
    public ApiResponse<Map<String, Object>> detail(HttpServletRequest request, @PathVariable long id) {
        long userId = store.requireUser(request.getHeader("Authorization"));
        return ApiResponse.success(store.teamDetail(id, userId));
    }

    @PostMapping("/join")
    public ApiResponse<Map<String, Object>> join(HttpServletRequest request, @RequestBody Map<String, Object> req) {
        long userId = store.requireUser(request.getHeader("Authorization"));
        return ApiResponse.success(store.joinTeam(userId, String.valueOf(req.getOrDefault("inviteCode", ""))));
    }

    @GetMapping("/{id}/members")
    public ApiResponse<Map<String, Object>> members(HttpServletRequest request, @PathVariable long id) {
        long userId = store.requireUser(request.getHeader("Authorization"));
        store.teamDetail(id, userId);
        return ApiResponse.success(Map.of("list", store.activeMembers(id), "total", store.activeMembers(id).size()));
    }

    @PutMapping("/{teamId}/members/{userId}/role")
    public ApiResponse<Map<String, Object>> role(HttpServletRequest request, @PathVariable long teamId, @PathVariable long userId, @RequestBody Map<String, Object> req) {
        long operatorId = store.requireUser(request.getHeader("Authorization"));
        store.changeRole(teamId, userId, operatorId, String.valueOf(req.getOrDefault("role", "member")));
        return ApiResponse.success(Map.of("ok", true));
    }

    @DeleteMapping("/{teamId}/members/{userId}")
    public ApiResponse<Map<String, Object>> remove(HttpServletRequest request, @PathVariable long teamId, @PathVariable long userId) {
        long operatorId = store.requireUser(request.getHeader("Authorization"));
        store.removeMember(teamId, userId, operatorId);
        return ApiResponse.success(Map.of("ok", true));
    }

    @PostMapping("/{id}/regenerate-invite-code")
    public ApiResponse<Map<String, Object>> regenerateInvite(HttpServletRequest request, @PathVariable long id) {
        long userId = store.requireUser(request.getHeader("Authorization"));
        return ApiResponse.success(store.regenerateInvite(id, userId));
    }
}
