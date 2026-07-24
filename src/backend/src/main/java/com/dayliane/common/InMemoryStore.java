package com.dayliane.common;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

public class InMemoryStore {
    private final AtomicLong userIds = new AtomicLong(1);
    private final AtomicLong scheduleIds = new AtomicLong(1);
    private final AtomicLong teamIds = new AtomicLong(1);
    private final AtomicLong memberIds = new AtomicLong(1);
    private final AtomicLong taskIds = new AtomicLong(1);
    private final AtomicLong assigneeIds = new AtomicLong(1);
    private final AtomicLong notificationIds = new AtomicLong(1);

    public final Map<Long, Map<String, Object>> users = new ConcurrentHashMap<>();
    public final Map<String, Long> phoneToUserId = new ConcurrentHashMap<>();
    public final Map<String, Long> accessTokens = new ConcurrentHashMap<>();
    public final Map<String, Long> refreshTokens = new ConcurrentHashMap<>();
    public final Map<Long, Map<String, Object>> schedules = new ConcurrentHashMap<>();
    public final Map<Long, Map<String, Object>> teams = new ConcurrentHashMap<>();
    public final Map<Long, Map<String, Object>> members = new ConcurrentHashMap<>();
    public final Map<String, Long> inviteCodeToTeamId = new ConcurrentHashMap<>();
    public final Map<Long, Map<String, Object>> teamTasks = new ConcurrentHashMap<>();
    public final Map<Long, Map<String, Object>> assignees = new ConcurrentHashMap<>();
    public final Map<Long, Map<String, Object>> notifications = new ConcurrentHashMap<>();

    public InMemoryStore() {
        seed();
    }

    public long nextUserId() { return userIds.getAndIncrement(); }
    public long nextScheduleId() { return scheduleIds.getAndIncrement(); }
    public long nextTeamId() { return teamIds.getAndIncrement(); }
    public long nextMemberId() { return memberIds.getAndIncrement(); }
    public long nextTaskId() { return taskIds.getAndIncrement(); }
    public long nextAssigneeId() { return assigneeIds.getAndIncrement(); }
    public long nextNotificationId() { return notificationIds.getAndIncrement(); }

    public String now() {
        return OffsetDateTime.now().toString();
    }

    public String issueAccessToken(long userId) {
        String token = "access-" + UUID.randomUUID();
        accessTokens.put(token, userId);
        return token;
    }

    public String issueRefreshToken(long userId) {
        String token = "refresh-" + UUID.randomUUID();
        refreshTokens.put(token, userId);
        return token;
    }

    public long requireUser(String authorization) {
        if (authorization == null || !authorization.startsWith("Bearer ")) {
            throw new BusinessException(401, "unauthorized");
        }
        Long userId = accessTokens.get(authorization.substring(7));
        if (userId == null) {
            throw new BusinessException(401, "unauthorized");
        }
        return userId;
    }

    public Map<String, Object> requireUserEntity(long userId) {
        Map<String, Object> user = users.get(userId);
        if (user == null || user.get("deletedAt") != null) {
            throw new BusinessException(404, "user not found");
        }
        return user;
    }

    public Map<String, Object> requireSchedule(long id, long userId) {
        Map<String, Object> schedule = schedules.get(id);
        if (schedule == null || schedule.get("deletedAt") != null || ((Number) schedule.get("userId")).longValue() != userId) {
            throw new BusinessException(404, "schedule not found");
        }
        return schedule;
    }

    public Map<String, Object> requireTeam(long teamId) {
        Map<String, Object> team = teams.get(teamId);
        if (team == null || team.get("deletedAt") != null) {
            throw new BusinessException(404, "team not found");
        }
        return team;
    }

    public Map<String, Object> requireTask(long taskId) {
        Map<String, Object> task = teamTasks.get(taskId);
        if (task == null || task.get("deletedAt") != null) {
            throw new BusinessException(404, "team task not found");
        }
        return task;
    }

    public Map<String, Object> requireActiveMember(long teamId, long userId) {
        return members.values().stream()
                .filter(m -> ((Number) m.get("teamId")).longValue() == teamId)
                .filter(m -> ((Number) m.get("userId")).longValue() == userId)
                .filter(m -> "active".equals(m.get("status")))
                .findFirst()
                .orElseThrow(() -> new BusinessException(403, "not a team member"));
    }

    public boolean isActiveMember(long teamId, long userId) {
        return members.values().stream().anyMatch(m -> ((Number) m.get("teamId")).longValue() == teamId
                && ((Number) m.get("userId")).longValue() == userId
                && "active".equals(m.get("status")));
    }

    public String role(long teamId, long userId) {
        return String.valueOf(requireActiveMember(teamId, userId).get("role"));
    }

    public boolean canManageTeam(long teamId, long userId) {
        String role = role(teamId, userId);
        return "owner".equals(role) || "admin".equals(role);
    }

    public List<Map<String, Object>> activeMembers(long teamId) {
        return members.values().stream()
                .filter(m -> ((Number) m.get("teamId")).longValue() == teamId)
                .filter(m -> "active".equals(m.get("status")))
                .sorted(Comparator.comparing(m -> ((Number) m.get("id")).longValue()))
                .map(this::memberView)
                .toList();
    }

    public List<Map<String, Object>> activeAssignees(long taskId) {
        return assignees.values().stream()
                .filter(a -> ((Number) a.get("taskId")).longValue() == taskId)
                .filter(a -> Boolean.TRUE.equals(a.get("isActive")))
                .sorted(Comparator.comparing(a -> ((Number) a.get("id")).longValue()))
                .toList();
    }

    public Map<String, Object> myActiveAssignee(long taskId, long userId) {
        return activeAssignees(taskId).stream()
                .filter(a -> ((Number) a.get("userId")).longValue() == userId)
                .findFirst()
                .orElseThrow(() -> new BusinessException(403, "not task assignee"));
    }

    public List<Map<String, Object>> page(List<Map<String, Object>> input, int page, int size) {
        int safePage = Math.max(1, page);
        int safeSize = Math.min(100, Math.max(1, size));
        int from = Math.max(0, (safePage - 1) * safeSize);
        int to = Math.min(input.size(), from + safeSize);
        if (from >= input.size()) {
            return List.of();
        }
        return input.subList(from, to);
    }

    public Map<String, Object> pageResult(List<Map<String, Object>> list, int page, int size) {
        return Map.of("list", page(list, page, size), "total", list.size(), "page", Math.max(1, page), "size", Math.min(100, Math.max(1, size)));
    }

    public void addNotification(long userId, String type, String title, String content, String relatedType, Long relatedId) {
        long id = nextNotificationId();
        Map<String, Object> item = new ConcurrentHashMap<>();
        item.put("id", id);
        item.put("userId", userId);
        item.put("type", type);
        item.put("title", title);
        item.put("content", content);
        item.put("relatedType", relatedType);
        item.put("relatedId", relatedId == null ? 0L : relatedId);
        item.put("reminderId", 0L);
        item.put("isRead", false);
        item.put("createdAt", now());
        notifications.put(id, item);
    }

    public void recalculateTaskStatus(long taskId) {
        Map<String, Object> task = requireTask(taskId);
        if ("cancelled".equals(task.get("status"))) {
            return;
        }
        List<Map<String, Object>> active = activeAssignees(taskId);
        if (active.isEmpty()) {
            task.put("status", "active");
            return;
        }
        boolean hasOpen = active.stream().anyMatch(a -> List.of("pending", "accepted").contains(String.valueOf(a.get("status"))));
        boolean allRejected = active.stream().allMatch(a -> "rejected".equals(a.get("status")));
        if (hasOpen) {
            task.put("status", "active");
        } else if (allRejected) {
            task.put("status", "all_rejected");
        } else {
            task.put("status", "completed");
        }
    }

    public Map<String, Object> userView(long userId) {
        Map<String, Object> user = requireUserEntity(userId);
        Map<String, Object> out = new LinkedHashMap<>();
        for (String key : List.of("id", "phone", "nickname", "avatarUrl", "timezone", "status", "createdAt")) {
            out.put(key, user.get(key));
        }
        return out;
    }

    public Map<String, Object> memberView(Map<String, Object> member) {
        long userId = ((Number) member.get("userId")).longValue();
        Map<String, Object> out = new LinkedHashMap<>(member);
        out.put("user", userView(userId));
        out.put("nickname", requireUserEntity(userId).get("nickname"));
        return out;
    }

    public boolean sameDate(String iso, LocalDate date) {
        if (iso == null || iso.isBlank()) {
            return false;
        }
        try {
            return OffsetDateTime.parse(iso).toLocalDate().equals(date);
        } catch (DateTimeParseException ex) {
            return iso.startsWith(date.toString());
        }
    }

    public String inviteCode() {
        return UUID.randomUUID().toString().replace("-", "").substring(0, 6).toUpperCase();
    }

    private void seed() {
        long userId = nextUserId();
        Map<String, Object> user = new ConcurrentHashMap<>();
        user.put("id", userId);
        user.put("phone", "13800138000");
        user.put("password", "Abc12345");
        user.put("nickname", "Zhang San");
        user.put("avatarUrl", "");
        user.put("timezone", "Asia/Shanghai");
        user.put("status", "active");
        user.put("createdAt", now());
        users.put(userId, user);
        phoneToUserId.put("13800138000", userId);

        long userId2 = nextUserId();
        Map<String, Object> user2 = new ConcurrentHashMap<>();
        user2.put("id", userId2);
        user2.put("phone", "13900139000");
        user2.put("password", "Abc12345");
        user2.put("nickname", "Li Si");
        user2.put("avatarUrl", "");
        user2.put("timezone", "Asia/Shanghai");
        user2.put("status", "active");
        user2.put("createdAt", now());
        users.put(userId2, user2);
        phoneToUserId.put("13900139000", userId2);

        long scheduleId = nextScheduleId();
        Map<String, Object> schedule = new ConcurrentHashMap<>();
        schedule.put("id", scheduleId);
        schedule.put("userId", userId);
        schedule.put("title", "Project weekly meeting");
        schedule.put("description", "Discuss MVP progress");
        schedule.put("groupName", "Work");
        schedule.put("timeType", "point_event");
        schedule.put("startTime", OffsetDateTime.now().plusHours(2).toString());
        schedule.put("endTime", OffsetDateTime.now().plusHours(3).toString());
        schedule.put("deadlineTime", "");
        schedule.put("status", "pending");
        schedule.put("hasReminder", true);
        schedule.put("createdAt", now());
        schedules.put(scheduleId, schedule);

        long teamId = nextTeamId();
        Map<String, Object> team = new ConcurrentHashMap<>();
        team.put("id", teamId);
        team.put("name", "Product Design Team");
        team.put("inviteCode", "K8F2QX");
        team.put("inviteCodeExpireAt", OffsetDateTime.now().plusDays(15).toString());
        team.put("ownerId", userId);
        team.put("status", "active");
        team.put("createdAt", now());
        teams.put(teamId, team);
        inviteCodeToTeamId.put("K8F2QX", teamId);

        Map<String, Object> owner = new ConcurrentHashMap<>();
        owner.put("id", nextMemberId());
        owner.put("teamId", teamId);
        owner.put("userId", userId);
        owner.put("role", "owner");
        owner.put("status", "active");
        owner.put("joinedAt", now());
        members.put(((Number) owner.get("id")).longValue(), owner);

        Map<String, Object> member = new ConcurrentHashMap<>();
        member.put("id", nextMemberId());
        member.put("teamId", teamId);
        member.put("userId", userId2);
        member.put("role", "member");
        member.put("status", "active");
        member.put("joinedAt", now());
        members.put(((Number) member.get("id")).longValue(), member);

        long taskId = nextTaskId();
        Map<String, Object> task = new ConcurrentHashMap<>();
        task.put("id", taskId);
        task.put("teamId", teamId);
        task.put("creatorId", userId);
        task.put("title", "Review interaction draft");
        task.put("description", "Check schedule card and duration task display");
        task.put("groupName", "Team Task");
        task.put("startTime", OffsetDateTime.now().plusHours(1).toString());
        task.put("deadlineTime", OffsetDateTime.now().plusDays(2).toString());
        task.put("status", "active");
        task.put("createdAt", now());
        teamTasks.put(taskId, task);

        Map<String, Object> assignee = new ConcurrentHashMap<>();
        assignee.put("id", nextAssigneeId());
        assignee.put("taskId", taskId);
        assignee.put("teamId", teamId);
        assignee.put("userId", userId2);
        assignee.put("status", "pending");
        assignee.put("isActive", true);
        assignee.put("assignRound", 1);
        assignee.put("assignedAt", now());
        assignees.put(((Number) assignee.get("id")).longValue(), assignee);
        addNotification(userId2, "task_assigned", "New team task", "You have been assigned: Review interaction draft", "team_task", taskId);
    }
}

