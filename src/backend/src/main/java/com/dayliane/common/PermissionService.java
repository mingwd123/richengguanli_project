package com.dayliane.common;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

@Component
public class PermissionService {

    private final JdbcTemplate jdbc;

    public PermissionService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public void requireScheduleOwner(long scheduleId, long userId) {
        Integer count = jdbc.queryForObject(
                "select count(*) from schedule where id=? and user_id=? and deleted_at is null",
                Integer.class, scheduleId, userId);
        if (count == null || count == 0) {
            throw new BusinessException(404, "schedule not found");
        }
    }

    public void requireActiveMember(long teamId, long userId) {
        Integer count = jdbc.queryForObject(
                "select count(*) from team_member where team_id=? and user_id=? and status='active'",
                Integer.class, teamId, userId);
        if (count == null || count == 0) {
            throw new BusinessException(403, "not a team member");
        }
    }

    public void requireTeamManager(long teamId, long userId) {
        String role;
        try {
            role = jdbc.queryForObject(
                    "select role from team_member where team_id=? and user_id=? and status='active'",
                    String.class, teamId, userId);
        } catch (Exception e) {
            throw new BusinessException(403, "not a team member");
        }
        if (!"owner".equals(role) && !"admin".equals(role)) {
            throw new BusinessException(403, "only team manager can perform this action");
        }
    }

    public void requireTeamTaskAssignee(long taskId, long userId) {
        Integer count = jdbc.queryForObject(
                "select count(*) from team_task_assignee where task_id=? and user_id=? and is_active=true",
                Integer.class, taskId, userId);
        if (count == null || count == 0) {
            throw new BusinessException(403, "not task assignee");
        }
    }

    public void requireTeamTaskManager(long taskId, long userId) {
        try {
            Long teamId = jdbc.queryForObject(
                    "select team_id from team_task where id=? and deleted_at is null",
                    Long.class, taskId);
            if (teamId == null) throw new BusinessException(404, "team task not found");

            Long creatorId = jdbc.queryForObject(
                    "select creator_id from team_task where id=?",
                    Long.class, taskId);

            if (creatorId != null && creatorId == userId) return;

            requireTeamManager(teamId, userId);
        } catch (RuntimeException e) {
            if (e instanceof BusinessException) throw e;
            throw new BusinessException(404, "team task not found");
        }
    }

    public boolean isActiveMember(long teamId, long userId) {
        Integer count = jdbc.queryForObject(
                "select count(*) from team_member where team_id=? and user_id=? and status='active'",
                Integer.class, teamId, userId);
        return count != null && count > 0;
    }

    public String role(long teamId, long userId) {
        try {
            return jdbc.queryForObject(
                    "select role from team_member where team_id=? and user_id=? and status='active'",
                    String.class, teamId, userId);
        } catch (Exception e) {
            throw new BusinessException(403, "not a team member");
        }
    }

    public boolean canManageTeam(long teamId, long userId) {
        String r = role(teamId, userId);
        return "owner".equals(r) || "admin".equals(r);
    }
}
