package com.dayliane.teamtask;

import com.dayliane.common.BusinessException;
import com.dayliane.common.PermissionService;
import com.dayliane.notification.NotificationService;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.sql.PreparedStatement;
import java.sql.Statement;
import java.sql.Timestamp;
import java.time.*;
import java.util.*;

@Service
public class TeamTaskService {

    private final JdbcTemplate jdbc;
    private final PermissionService permissionService;
    private final NotificationService notificationService;

    public TeamTaskService(JdbcTemplate jdbc, PermissionService permissionService, NotificationService notificationService) {
        this.jdbc = jdbc;
        this.permissionService = permissionService;
        this.notificationService = notificationService;
    }

    // ===== Public API Methods =====

    @Transactional
    public Map<String, Object> createTeamTask(long userId, Map<String, Object> req) {
        long teamId = number(req.get("teamId"));
        permissionService.requireActiveMember(teamId, userId);
        String title = text(req, "title");
        if (title.isBlank()) throw new BusinessException(400, "title is required");
        Object raw = req.get("assigneeUserIds");
        List<?> assigneeIds = raw instanceof List<?> list ? list : List.of();
        if (assigneeIds.isEmpty()) throw new BusinessException(400, "assigneeUserIds is required");
        for (Object v : assigneeIds) permissionService.requireActiveMember(teamId, number(v));
        Map<String, Object> group = resolveTeamTaskGroup(teamId, req);
        int sortOrder = nextTeamTaskSortOrder(teamId, longValue(group.get("id")));
        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbc.update(con -> {
            PreparedStatement ps = con.prepareStatement("insert into team_task (team_id,creator_id,title,description,group_id,group_name,sort_order,start_time,deadline_time,status,updated_by) values (?,?,?,?,?,?,?,?,?, 'active',?)", new String[]{"id"});
            ps.setLong(1, teamId);
            ps.setLong(2, userId);
            ps.setString(3, title);
            ps.setString(4, text(req, "description"));
            ps.setLong(5, longValue(group.get("id")));
            ps.setString(6, String.valueOf(group.get("name")));
            ps.setInt(7, sortOrder);
            ps.setTimestamp(8, parseTime(text(req, "startTime")));
            ps.setTimestamp(9, parseTime(text(req, "deadlineTime")));
            ps.setLong(10, userId);
            return ps;
        }, keyHolder);
        long taskId = Objects.requireNonNull(keyHolder.getKey()).longValue();
        recordEvent(taskId, userId, "created", "创建了任务");
        for (Object v : assigneeIds) {
            long assigneeUserId = number(v);
            jdbc.update("insert into team_task_assignee (task_id,user_id,assign_round,is_active,status,assigned_by,assigned_at) values (?,?,1,true,'pending',?,utc_timestamp())", taskId, assigneeUserId, userId);
            recordEvent(taskId, userId, "assigned", "分配给 " + requireUserEntity(assigneeUserId).get("nickname"));
            notificationService.createNotification(assigneeUserId, "task_assigned", "收到新的团队任务", "你被分配了任务：" + title, "team_task", taskId, null);
            createTeamTaskReminders(assigneeUserId, taskId, req);
        }
        return teamTaskSummary(taskId, userId);
    }

    public Map<String, Object> listMyTeamTasks(long userId, int page, int size, String assignStatus, String keyword, String dateFrom, String dateTo) {
        return listMyTeamTasks(userId, page, size, assignStatus, keyword, dateFrom, dateTo, "manual");
    }

    public Map<String, Object> listMyTeamTasks(long userId, int page, int size, String assignStatus, String keyword, String dateFrom, String dateTo, String sort) {
        StringBuilder sql = new StringBuilder("select t.id from team_task t join team_task_assignee a on a.task_id=t.id join team_member m on m.team_id=t.team_id and m.user_id=? and m.status='active' where a.user_id=? and a.is_active=true and t.deleted_at is null");
        List<Object> params = new ArrayList<>(List.of(userId, userId));
        if (!blank(assignStatus)) { sql.append(" and a.status=?"); params.add(assignStatus); }
        if (!blank(keyword)) { sql.append(" and t.title like ?"); params.add("%" + keyword + "%"); }
        if (!blank(dateFrom)) { sql.append(" and coalesce(t.deadline_time,t.start_time,t.created_at) >= ?"); params.add(dateFrom + " 00:00:00"); }
        if (!blank(dateTo)) { sql.append(" and coalesce(t.deadline_time,t.start_time,t.created_at) <= ?"); params.add(dateTo + " 23:59:59"); }
        sql.append(" order by ").append("completed".equals(assignStatus) && "manual".equals(sort) ? "t.sort_order asc,t.id asc" : teamTaskOrder(sort, false));
        List<Map<String, Object>> rows = jdbc.query(sql.toString(), (rs, i) -> teamTaskSummary(rs.getLong("id"), userId), params.toArray());
        return pageResult(rows, page, size);
    }

    public Map<String, Object> listCreatedTeamTasks(long userId, int page, int size, String status, String keyword, String dateFrom, String dateTo) {
        return listCreatedTeamTasks(userId, page, size, status, keyword, dateFrom, dateTo, "manual");
    }

    public Map<String, Object> listCreatedTeamTasks(long userId, int page, int size, String status, String keyword, String dateFrom, String dateTo, String sort) {
        StringBuilder sql = new StringBuilder("select t.id from team_task t join team_member m on m.team_id=t.team_id and m.user_id=? and m.status='active' where t.creator_id=? and t.deleted_at is null");
        List<Object> params = new ArrayList<>(List.of(userId, userId));
        if (!blank(status)) { sql.append(" and t.status=?"); params.add(status); }
        if (!blank(keyword)) { sql.append(" and t.title like ?"); params.add("%" + keyword + "%"); }
        if (!blank(dateFrom)) { sql.append(" and coalesce(t.deadline_time,t.start_time,t.created_at) >= ?"); params.add(dateFrom + " 00:00:00"); }
        if (!blank(dateTo)) { sql.append(" and coalesce(t.deadline_time,t.start_time,t.created_at) <= ?"); params.add(dateTo + " 23:59:59"); }
        sql.append(" order by ").append("completed".equals(status) && "manual".equals(sort) ? "t.sort_order asc,t.id asc" : teamTaskOrder(sort, true));
        List<Map<String, Object>> rows = jdbc.query(sql.toString(), (rs, i) -> teamTaskSummary(rs.getLong("id"), userId), params.toArray());
        return pageResult(rows, page, size);
    }

    public Map<String, Object> listTeamTasks(long teamId, long userId, int page, int size, String status, String keyword, String dateFrom, String dateTo) {
        return listTeamTasks(teamId, userId, page, size, status, keyword, dateFrom, dateTo, "manual");
    }

    public Map<String, Object> listTeamTasks(long teamId, long userId, int page, int size, String status, String keyword, String dateFrom, String dateTo, String sort) {
        permissionService.requireActiveMember(teamId, userId);
        StringBuilder sql = new StringBuilder("select t.id from team_task t where t.team_id=? and t.deleted_at is null");
        List<Object> params = new ArrayList<>();
        params.add(teamId);
        if (!blank(status)) { sql.append(" and t.status=?"); params.add(status); }
        if (!blank(keyword)) { sql.append(" and t.title like ?"); params.add("%" + keyword + "%"); }
        if (!blank(dateFrom)) { sql.append(" and coalesce(t.deadline_time,t.start_time,t.created_at) >= ?"); params.add(dateFrom + " 00:00:00"); }
        if (!blank(dateTo)) { sql.append(" and coalesce(t.deadline_time,t.start_time,t.created_at) <= ?"); params.add(dateTo + " 23:59:59"); }
        sql.append(" order by ").append("completed".equals(status) && "manual".equals(sort) ? "t.sort_order asc,t.id asc" : teamTaskOrder(sort, true));
        List<Map<String, Object>> rows = jdbc.query(sql.toString(), (rs, i) -> teamTaskSummary(rs.getLong("id"), userId), params.toArray());
        return pageResult(rows, page, size);
    }

    private static String teamTaskOrder(String sort, boolean groupedManualOrder) {
        return switch (sort == null ? "manual" : sort) {
            case "manual" -> groupedManualOrder
                    ? "t.group_id asc, t.sort_order asc, coalesce(t.deadline_time,t.start_time,t.created_at) asc, t.id asc"
                    : "coalesce(t.deadline_time,t.start_time,t.created_at) asc, t.id asc";
            case "time_asc" -> "coalesce(t.deadline_time,t.start_time,t.created_at) asc, t.id asc";
            case "time_desc" -> "coalesce(t.deadline_time,t.start_time,t.created_at) desc, t.id desc";
            case "created_desc" -> "t.created_at desc, t.id desc";
            case "title_asc" -> "t.title asc, t.id asc";
            default -> throw new BusinessException(400, "sort is invalid");
        };
    }

    public Map<String, Object> teamTaskDetail(long taskId, long userId) {
        Map<String, Object> task = requireTeamTask(taskId);
        long teamId = longValue(task.get("teamId"));
        permissionService.requireActiveMember(teamId, userId);
        Map<String, Object> creator = userView(longValue(task.get("creatorId")));
        task.put("teamName", requireTeam(teamId).get("name"));
        task.put("assignees", teamTaskAssignees(taskId));
        task.put("events", teamTaskEvents(taskId));
        task.put("creator", creator);
        task.put("creatorName", creator.get("nickname"));
        task.put("canManage", longValue(task.get("creatorId")) == userId || permissionService.canManageTeam(teamId, userId));
        task.put("pendingReminders", jdbc.query("select id,user_id userId,remind_at remindAt,status from reminder where target_type='team_task' and target_id=? and status='pending' order by remind_at,user_id", (rs, i) -> Map.of(
                "id", rs.getLong("id"), "userId", rs.getLong("userId"), "remindAt", iso(rs.getTimestamp("remindAt")), "status", rs.getString("status")), taskId));
        return task;
    }

    @Transactional
    public Map<String, Object> teamTaskAssigneeTransition(long taskId, long userId, String next, List<String> allowedFrom) {
        Map<String, Object> task = requireTeamTask(taskId);
        if ("cancelled".equals(task.get("status"))) throw new BusinessException(400, "cancelled task cannot be operated");
        Map<String, Object> a = requireMyActiveAssignee(taskId, userId);
        String current = String.valueOf(a.get("status"));
        if (!allowedFrom.contains(current)) throw new BusinessException(400, "invalid status transition");
        String timeColumn = switch (next) { case "accepted" -> "accepted_at"; case "rejected" -> "rejected_at"; case "completed" -> "completed_at"; default -> "status_updated_at"; };
        jdbc.update("update team_task_assignee set status=?, " + timeColumn + "=utc_timestamp(), status_updated_by=?, status_updated_at=utc_timestamp() where id=?", next, userId, a.get("id"));
        recordEvent(taskId, userId, next, actionLabel(next));
        recalculateTeamTaskStatus(taskId);
        notifyCreator(task, userId, next);
        if ("rejected".equals(next)) {
            cancelPendingReminders("team_task", taskId, userId);
        }
        if ("completed".equals(String.valueOf(requireTeamTask(taskId).get("status")))) {
            cancelPendingReminders("team_task", taskId, null);
        }
        return teamTaskDetail(taskId, userId);
    }

    @Transactional
    public Map<String, Object> updateTeamTask(long taskId, long userId, Map<String, Object> req) {
        requireTeamTaskManager(taskId, userId);
        Map<String, Object> task = requireTeamTask(taskId);
        boolean hasTitle = req.containsKey("title");
        String title = hasTitle ? text(req, "title").trim() : null;
        if (hasTitle && title.isBlank()) throw new BusinessException(400, "title is required");
        boolean hasDescription = req.containsKey("description");
        String description = hasDescription ? text(req, "description") : null;
        boolean hasStartTime = req.containsKey("startTime");
        boolean hasDeadlineTime = req.containsKey("deadlineTime");
        Timestamp startTime = parseEditableTime(req.get("startTime"), "startTime");
        Timestamp deadlineTime = parseEditableTime(req.get("deadlineTime"), "deadlineTime");
        Map<String, Object> group = req.containsKey("groupId") || req.containsKey("groupName") ? resolveTeamTaskGroup(longValue(task.get("teamId")), req) : null;
        Integer sortOrder = group == null ? null : nextTeamTaskSortOrder(longValue(task.get("teamId")), longValue(group.get("id")));
        jdbc.update("update team_task set title=case when ? then ? else title end,description=case when ? then ? else description end,group_id=coalesce(?,group_id),group_name=coalesce(?,group_name),sort_order=coalesce(?,sort_order),start_time=case when ? then ? else start_time end,deadline_time=case when ? then ? else deadline_time end,time_updated_at=case when ? then utc_timestamp() else time_updated_at end,updated_by=? where id=? and deleted_at is null",
                hasTitle, title, hasDescription, description, group == null ? null : longValue(group.get("id")), group == null ? null : String.valueOf(group.get("name")), sortOrder,
                hasStartTime, startTime, hasDeadlineTime, deadlineTime, hasStartTime || hasDeadlineTime, userId, taskId);
        if (req.containsKey("remindAt") || req.containsKey("remindAts")) {
            cancelPendingReminders("team_task", taskId, null);
            List<Long> assigneeIds = jdbc.queryForList("select user_id from team_task_assignee where task_id=? and is_active=true and status in ('pending','accepted')", Long.class, taskId);
            for (Long assigneeId : assigneeIds) createTeamTaskReminders(assigneeId, taskId, req);
        }
        recordEvent(taskId, userId, "updated", "更新了任务信息");
        return teamTaskDetail(taskId, userId);
    }

    @Transactional
    public Map<String, Object> moveTeamTaskGroup(long taskId, long userId, Map<String, Object> req) {
        requireTeamTaskManager(taskId, userId);
        Map<String, Object> task = requireTeamTask(taskId);
        long teamId = longValue(task.get("teamId"));
        long groupId = requiredId(req, "groupId");
        Map<String, Object> group = requireTeamTaskGroup(teamId, groupId);
        jdbc.update("update team_task set group_id=?, group_name=?, sort_order=?, updated_by=? where id=? and deleted_at is null", groupId, group.get("name"), nextTeamTaskSortOrder(teamId, groupId), userId, taskId);
        return teamTaskDetail(taskId, userId);
    }

    @Transactional
    public Map<String, Object> updateTeamTaskTime(long taskId, long userId, Map<String, Object> req) {
        requireTeamTaskManager(taskId, userId);
        boolean hasStartTime = req.containsKey("startTime");
        boolean hasDeadlineTime = req.containsKey("deadlineTime");
        if (!hasStartTime && !hasDeadlineTime) throw new BusinessException(400, "startTime or deadlineTime is required");
        jdbc.update("update team_task set start_time=case when ? then ? else start_time end,deadline_time=case when ? then ? else deadline_time end,time_updated_at=utc_timestamp(),updated_by=? where id=? and deleted_at is null",
                hasStartTime, parseEditableTime(req.get("startTime"), "startTime"), hasDeadlineTime, parseEditableTime(req.get("deadlineTime"), "deadlineTime"), userId, taskId);
        recordEvent(taskId, userId, "time_updated", "更新了任务时间");
        return teamTaskDetail(taskId, userId);
    }

    @Transactional
    public void deleteTeamTask(long taskId, long userId) {
        requireTeamTaskManager(taskId, userId);
        jdbc.update("update team_task set deleted_at=utc_timestamp(), deleted_by=? where id=?", userId, taskId);
        cancelPendingReminders("team_task", taskId, null);
        recordEvent(taskId, userId, "deleted", "删除了任务");
    }

    @Transactional
    public Map<String, Object> cancelTeamTask(long taskId, long userId) {
        requireTeamTaskManager(taskId, userId);
        Map<String, Object> task = requireTeamTask(taskId);
        jdbc.update("update team_task set status='cancelled', updated_by=? where id=?", userId, taskId);
        recordEvent(taskId, userId, "cancelled", "取消了任务");
        cancelPendingReminders("team_task", taskId, null);
        notifyAssignees(taskId, userId, "task_cancelled", "Task cancelled", "Task cancelled: " + task.get("title"));
        return teamTaskSummary(taskId, userId);
    }

    @Transactional
    public Map<String, Object> restoreTeamTask(long taskId, long userId) {
        Map<String, Object> task = requireTeamTask(taskId);
        if (!permissionService.canManageTeam(longValue(task.get("teamId")), userId)) throw new BusinessException(403, "only team manager can restore task");
        jdbc.update("update team_task set status='active', updated_by=? where id=?", userId, taskId);
        recordEvent(taskId, userId, "restored", "恢复了任务");
        recalculateTeamTaskStatus(taskId);
        return teamTaskSummary(taskId, userId);
    }

    @Transactional
    public Map<String, Object> reassignTeamTask(long taskId, long userId, long originalUserId, long newUserId) {
        requireTeamTaskManager(taskId, userId);
        Map<String, Object> task = requireTeamTask(taskId);
        long teamId = longValue(task.get("teamId"));
        permissionService.requireActiveMember(teamId, newUserId);
        Map<String, Object> old;
        try {
            old = jdbc.queryForObject("select id,assign_round assignRound,status from team_task_assignee where task_id=? and user_id=? and is_active=true", (rs, i) -> Map.of("id", rs.getLong("id"), "assignRound", rs.getInt("assignRound"), "status", rs.getString("status")), taskId, originalUserId);
        } catch (EmptyResultDataAccessException ex) {
            throw new BusinessException(404, "original assignee not found");
        }
        if (!"rejected".equals(old.get("status"))) throw new BusinessException(400, "only rejected assignee can be reassigned");
        jdbc.update("update team_task_assignee set is_active=false where id=?", old.get("id"));
        jdbc.update("insert into team_task_assignee (task_id,user_id,assign_round,is_active,status,reassigned_from_user_id,assigned_by,assigned_at) values (?,?,?,true,'pending',?,?,utc_timestamp())",
                taskId, newUserId, ((Number) old.get("assignRound")).intValue() + 1, originalUserId, userId);
        notificationService.createNotification(newUserId, "task_assigned", "团队任务已重新分配",
                "你被分配了任务：" + task.get("title"), "team_task", taskId, null);
        recordEvent(taskId, userId, "reassigned", "将任务从 " + requireUserEntity(originalUserId).get("nickname") + " 重新分配给 " + requireUserEntity(newUserId).get("nickname"));
        createReassignedReminder(newUserId, taskId, task);
        recalculateTeamTaskStatus(taskId);
        return teamTaskDetail(taskId, userId);
    }

    public Map<String, Object> correctTeamTaskAssigneeStatus(long taskId, long assigneeId, long userId, String status) {
        requireTeamTaskManager(taskId, userId);
        if (!List.of("pending", "accepted", "rejected", "completed").contains(status)) throw new BusinessException(400, "status is invalid");
        int updated = jdbc.update("update team_task_assignee set status=?, status_updated_by=?, status_updated_at=utc_timestamp() where id=? and task_id=?", status, userId, assigneeId, taskId);
        if (updated == 0) throw new BusinessException(404, "assignee not found");
        recalculateTeamTaskStatus(taskId);
        return teamTaskDetail(taskId, userId);
    }

    @Transactional
    public Map<String, Object> createTeamTaskGroup(long teamId, long userId, Map<String, Object> req) {
        permissionService.requireTeamManager(teamId, userId);
        String name = text(req, "name").trim();
        validateTaskGroupName(name);
        if (count("select count(*) from task_group where team_id=? and scope='team' and name=? and deleted_at is null", teamId, name) > 0) throw new BusinessException(409, "task group already exists");
        Integer sortOrder = jdbc.queryForObject("select coalesce(max(sort_order),0)+10 from task_group where team_id=? and scope='team' and deleted_at is null", Integer.class, teamId);
        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbc.update(con -> {
            PreparedStatement ps = con.prepareStatement("insert into task_group (team_id,scope,name,sort_order,is_default) values (?,'team',?,?,false)", new String[]{"id"});
            ps.setLong(1, teamId); ps.setString(2, name); ps.setInt(3, sortOrder == null ? 10 : sortOrder); return ps;
        }, keyHolder);
        return requireTeamTaskGroup(teamId, Objects.requireNonNull(keyHolder.getKey()).longValue());
    }

    @Transactional
    public Map<String, Object> listTeamTaskGroups(long teamId, long userId) {
        permissionService.requireActiveMember(teamId, userId);
        createDefaultTeamTaskGroup(teamId);
        List<Map<String, Object>> rows = jdbc.query("select id,user_id userId,team_id teamId,scope,name,sort_order sortOrder,is_default isDefault,created_at createdAt from task_group where team_id=? and scope='team' and deleted_at is null order by sort_order,id", taskGroupMapper(), teamId);
        return Map.of("list", rows, "total", rows.size());
    }

    @Transactional
    public Map<String, Object> updateTeamTaskGroup(long teamId, long groupId, long userId, Map<String, Object> req) {
        permissionService.requireTeamManager(teamId, userId);
        requireTeamTaskGroup(teamId, groupId);
        String name = text(req, "name").trim();
        validateTaskGroupName(name);
        if (count("select count(*) from task_group where team_id=? and scope='team' and name=? and id<>? and deleted_at is null", teamId, name, groupId) > 0) throw new BusinessException(409, "task group already exists");
        jdbc.update("update task_group set name=? where id=? and team_id=? and scope='team' and deleted_at is null", name, groupId, teamId);
        jdbc.update("update team_task set group_name=? where team_id=? and group_id=? and deleted_at is null", name, teamId, groupId);
        return requireTeamTaskGroup(teamId, groupId);
    }

    @Transactional
    public void deleteTeamTaskGroup(long teamId, long groupId, long userId, Map<String, Object> req) {
        permissionService.requireTeamManager(teamId, userId);
        requireTeamTaskGroup(teamId, groupId);
        List<Map<String, Object>> candidates = jdbc.query("select id,user_id userId,team_id teamId,scope,name,sort_order sortOrder,is_default isDefault,created_at createdAt from task_group where team_id=? and scope='team' and id<>? and deleted_at is null order by sort_order,id", taskGroupMapper(), teamId, groupId);
        if (candidates.isEmpty()) throw new BusinessException(400, "at least one task group is required");
        Map<String, Object> target;
        if (req != null && req.get("targetGroupId") != null && !String.valueOf(req.get("targetGroupId")).isBlank()) {
            target = requireTeamTaskGroup(teamId, number(req.get("targetGroupId")));
            if (longValue(target.get("id")) == groupId) throw new BusinessException(400, "targetGroupId must be another task group");
        } else target = candidates.get(0);
        List<Long> taskIds = jdbc.queryForList("select id from team_task where team_id=? and group_id=? and deleted_at is null order by sort_order,id", Long.class, teamId, groupId);
        int sortOrder = nextTeamTaskSortOrder(teamId, longValue(target.get("id")));
        for (Long taskId : taskIds) {
            jdbc.update("update team_task set group_id=?, group_name=?, sort_order=? where id=? and team_id=?", target.get("id"), target.get("name"), sortOrder, taskId, teamId);
            sortOrder += 10;
        }
        jdbc.update("update task_group set deleted_at=utc_timestamp() where id=? and team_id=? and scope='team' and deleted_at is null", groupId, teamId);
    }

    @Transactional
    public Map<String, Object> sortTeamTaskGroups(long teamId, long userId, Map<String, Object> req) {
        permissionService.requireTeamManager(teamId, userId);
        createDefaultTeamTaskGroup(teamId);
        List<Long> ids = idList(req, "groupIds");
        validateCompleteIds(ids, jdbc.queryForList("select id from task_group where team_id=? and scope='team' and deleted_at is null order by sort_order,id", Long.class, teamId), "groupIds");
        for (int i = 0; i < ids.size(); i++) jdbc.update("update task_group set sort_order=? where id=? and team_id=? and scope='team' and deleted_at is null", (i + 1) * 10, ids.get(i), teamId);
        return listTeamTaskGroups(teamId, userId);
    }

    @Transactional
    public Map<String, Object> sortTeamTasks(long teamId, long userId, Map<String, Object> req) {
        long groupId = requiredId(req, "groupId");
        requireTeamTaskGroup(teamId, groupId);
        List<Long> ids = idList(req, "taskIds");
        List<Long> all = jdbc.queryForList("select id from team_task where team_id=? and group_id=? and deleted_at is null order by sort_order,id", Long.class, teamId, groupId);
        reorderSubset(all, ids, "taskIds");
        for (Long taskId : ids) requireTeamTaskManager(taskId, userId);
        for (int i = 0; i < all.size(); i++) jdbc.update("update team_task set sort_order=?, updated_by=? where id=? and team_id=? and group_id=? and deleted_at is null", (i + 1) * 10, userId, all.get(i), teamId, groupId);
        return Map.of("groupId", groupId, "taskIds", all);
    }

    @Transactional
    public Map<String, Object> sortCompletedTeamTasks(long teamId, long userId, Map<String, Object> req) {
        permissionService.requireTeamManager(teamId, userId);
        List<Long> ids = idList(req, "taskIds");
        List<Long> all = jdbc.queryForList("select id from team_task where team_id=? and status='completed' and deleted_at is null order by sort_order,id", Long.class, teamId);
        reorderSubset(all, ids, "taskIds");
        for (int i = 0; i < all.size(); i++) {
            jdbc.update("update team_task set sort_order=?,updated_by=? where id=? and team_id=? and status='completed' and deleted_at is null",
                    (i + 1) * 10, userId, all.get(i), teamId);
        }
        return Map.of("taskIds", all);
    }

    // ===== Private Helpers =====

    private Map<String, Object> resolveTeamTaskGroup(long teamId, Map<String, Object> req) {
        Object rawGroupId = req.get("groupId");
        if (rawGroupId != null && !String.valueOf(rawGroupId).isBlank()) return requireTeamTaskGroup(teamId, number(rawGroupId));
        String groupName = text(req, "groupName").trim();
        if (!groupName.isBlank()) {
            try {
                return jdbc.queryForObject("select id,user_id userId,team_id teamId,scope,name,sort_order sortOrder,is_default isDefault,created_at createdAt from task_group where team_id=? and scope='team' and name=? and deleted_at is null", taskGroupMapper(), teamId, groupName);
            } catch (EmptyResultDataAccessException ex) {
                throw new BusinessException(404, "task group not found");
            }
        }
        createDefaultTeamTaskGroup(teamId);
        return jdbc.queryForObject("select id,user_id userId,team_id teamId,scope,name,sort_order sortOrder,is_default isDefault,created_at createdAt from task_group where team_id=? and scope='team' and name='团队任务' and deleted_at is null", taskGroupMapper(), teamId);
    }

    private Map<String, Object> requireTeamTaskGroup(long teamId, long groupId) {
        try {
            return jdbc.queryForObject("select id,user_id userId,team_id teamId,scope,name,sort_order sortOrder,is_default isDefault,created_at createdAt from task_group where id=? and team_id=? and scope='team' and deleted_at is null", taskGroupMapper(), groupId, teamId);
        } catch (EmptyResultDataAccessException ex) {
            throw new BusinessException(404, "task group not found");
        }
    }

    private void createDefaultTeamTaskGroup(long teamId) {
        if (count("select count(*) from task_group where team_id=? and scope='team' and name='团队任务' and deleted_at is null", teamId) == 0) {
            jdbc.update("insert into task_group (team_id,scope,name,sort_order,is_default) values (?,'team','团队任务',10,true)", teamId);
        }
    }

    private int nextTeamTaskSortOrder(long teamId, long groupId) {
        Integer value = jdbc.queryForObject("select coalesce(max(sort_order),0)+10 from team_task where team_id=? and group_id=? and deleted_at is null", Integer.class, teamId, groupId);
        return value == null ? 10 : value;
    }

    private RowMapper<Map<String, Object>> taskGroupMapper() {
        return (rs, i) -> {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", rs.getLong("id")); m.put("userId", rs.getObject("userId")); m.put("teamId", rs.getObject("teamId")); m.put("scope", rs.getString("scope"));
            m.put("name", rs.getString("name")); m.put("sortOrder", rs.getInt("sortOrder")); m.put("isDefault", rs.getBoolean("isDefault")); m.put("createdAt", iso(rs.getTimestamp("createdAt"))); return m;
        };
    }

    private List<Long> idList(Map<String, Object> req, String key) {
        Object raw = req.get(key);
        if (!(raw instanceof List<?> values)) raw = req.get("ids");
        if (!(raw instanceof List<?> values)) throw new BusinessException(400, key + " is required");
        List<Long> ids = new ArrayList<>();
        for (Object value : values) ids.add(number(value));
        return ids;
    }

    private void validateCompleteIds(List<Long> ids, List<Long> expected, String field) {
        if (ids.size() != expected.size() || new HashSet<>(ids).size() != ids.size() || !new HashSet<>(ids).equals(new HashSet<>(expected))) throw new BusinessException(400, field + " must contain the complete set of valid ids");
    }

    private long requiredId(Map<String, Object> req, String key) {
        Object value = req.get(key);
        if (value == null || String.valueOf(value).isBlank()) throw new BusinessException(400, key + " is required");
        return number(value);
    }

    private void validateTaskGroupName(String name) {
        if (name == null || name.isBlank()) throw new BusinessException(400, "task group name is required");
        if (name.length() > 50) throw new BusinessException(400, "task group name is too long");
    }

    private void requireTeamTaskManager(long taskId, long userId) {
        Map<String, Object> task = requireTeamTask(taskId);
        long teamId = longValue(task.get("teamId"));
        permissionService.requireActiveMember(teamId, userId);
        if (longValue(task.get("creatorId")) != userId && !permissionService.canManageTeam(teamId, userId)) {
            throw new BusinessException(403, "no permission to manage task");
        }
    }

    private Map<String, Object> requireTeamTask(long taskId) {
        try {
            return jdbc.queryForObject("select id,team_id teamId,creator_id creatorId,title,description,group_id groupId,group_name groupName,sort_order sortOrder,start_time startTime,deadline_time deadlineTime,status,created_at createdAt from team_task where id=? and deleted_at is null", teamTaskMapper(), taskId);
        } catch (EmptyResultDataAccessException ex) {
            throw new BusinessException(404, "team task not found");
        }
    }

    private Map<String, Object> teamTaskSummary(long taskId, long userId) {
        Map<String, Object> task = requireTeamTask(taskId);
        task.put("teamName", requireTeam(longValue(task.get("teamId"))).get("name"));
        task.put("assigneeCount", count("select count(*) from team_task_assignee where task_id=? and is_active=true", taskId));
        task.put("assignees", teamTaskAssignees(taskId));
        List<Timestamp> reminders = jdbc.query("select min(remind_at) remindAt from reminder where target_type='team_task' and target_id=? and user_id=? and status='pending'", (rs, i) -> rs.getTimestamp("remindAt"), taskId, userId);
        task.put("remindAt", reminders.isEmpty() ? "" : iso(reminders.get(0)));
        try {
            Map<String, Object> a = requireMyActiveAssignee(taskId, userId);
            task.put("assigneeId", a.get("id"));
            task.put("assignStatus", a.get("status"));
            task.put("assignRound", a.get("assignRound"));
        } catch (BusinessException ignored) {
        }
        return task;
    }

    private List<Map<String, Object>> teamTaskAssignees(long taskId) {
        return jdbc.query("select a.id,a.user_id userId,a.assign_round assignRound,a.is_active isActive,a.status,u.nickname,u.avatar_url avatarUrl from team_task_assignee a join `user` u on u.id=a.user_id where a.task_id=? and a.is_active=true order by a.id", (rs, i) -> {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", rs.getLong("id"));
            m.put("assigneeId", rs.getLong("id"));
            m.put("userId", rs.getLong("userId"));
            m.put("assignRound", rs.getInt("assignRound"));
            m.put("isActive", rs.getBoolean("isActive"));
            m.put("isCurrent", rs.getBoolean("isActive"));
            m.put("status", rs.getString("status"));
            m.put("assignStatus", rs.getString("status"));
            m.put("nickname", rs.getString("nickname"));
            m.put("avatarUrl", rs.getString("avatarUrl"));
            return m;
        }, taskId);
    }

    private Map<String, Object> requireMyActiveAssignee(long taskId, long userId) {
        try {
            return jdbc.queryForObject("select id,user_id userId,status,assign_round assignRound from team_task_assignee where task_id=? and user_id=? and is_active=true", (rs, i) -> Map.of("id", rs.getLong("id"), "userId", rs.getLong("userId"), "status", rs.getString("status"), "assignRound", rs.getInt("assignRound")), taskId, userId);
        } catch (EmptyResultDataAccessException ex) {
            throw new BusinessException(403, "not task assignee");
        }
    }

    private RowMapper<Map<String, Object>> teamTaskMapper() {
        return (rs, i) -> {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", rs.getLong("id"));
            m.put("teamId", rs.getLong("teamId"));
            m.put("creatorId", rs.getLong("creatorId"));
            m.put("title", rs.getString("title"));
            m.put("description", rs.getString("description"));
            m.put("groupId", rs.getObject("groupId"));
            m.put("groupName", rs.getString("groupName"));
            m.put("sortOrder", rs.getInt("sortOrder"));
            m.put("startTime", iso(rs.getTimestamp("startTime")));
            m.put("deadlineTime", iso(rs.getTimestamp("deadlineTime")));
            m.put("status", rs.getString("status"));
            m.put("createdAt", iso(rs.getTimestamp("createdAt")));
            return m;
        };
    }

    private List<Map<String, Object>> teamTaskEvents(long taskId) {
        return jdbc.query("select e.id,e.event_type eventType,e.content,e.created_at createdAt,u.nickname actorName from team_task_event e left join `user` u on u.id=e.actor_id where e.task_id=? order by e.created_at desc,e.id desc", (rs, i) -> {
            Map<String, Object> event = new LinkedHashMap<>();
            event.put("id", rs.getLong("id"));
            event.put("eventType", rs.getString("eventType"));
            event.put("content", rs.getString("content"));
            event.put("createdAt", iso(rs.getTimestamp("createdAt")));
            event.put("actorName", rs.getString("actorName"));
            return event;
        }, taskId);
    }

    private void recordEvent(long taskId, long actorId, String eventType, String content) {
        jdbc.update("insert into team_task_event (task_id,actor_id,event_type,content) values (?,?,?,?)", taskId, actorId, eventType, content);
    }

    private String actionLabel(String action) {
        return switch (action) {
            case "accepted" -> "接受了任务";
            case "rejected" -> "拒绝了任务";
            case "completed" -> "完成了任务";
            default -> "更新了任务";
        };
    }

    private void recalculateTeamTaskStatus(long taskId) {
        List<String> statuses = jdbc.queryForList("select status from team_task_assignee where task_id=? and is_active=true", String.class, taskId);
        if (statuses.isEmpty()) {
            jdbc.update("update team_task set status='active' where id=?", taskId);
            return;
        }
        boolean hasOpen = statuses.stream().anyMatch(s -> List.of("pending", "accepted").contains(s));
        boolean allRejected = statuses.stream().allMatch("rejected"::equals);
        jdbc.update("update team_task set status=? where id=?", hasOpen ? "active" : allRejected ? "all_rejected" : "completed", taskId);
    }

    public Map<String, Object> pageResult(List<Map<String, Object>> rows, int page, int size) {
        int p = Math.max(1, page);
        int s = Math.min(100, Math.max(1, size));
        int from = Math.min(rows.size(), (p - 1) * s);
        int to = Math.min(rows.size(), from + s);
        return Map.of("list", rows.subList(from, to), "total", rows.size(), "page", p, "size", s);
    }

    private void cancelPendingReminders(String type, long id, Long userId) {
        if (userId == null) jdbc.update("update reminder set status='cancelled' where target_type=? and target_id=? and status='pending'", type, id);
        else jdbc.update("update reminder set status='cancelled' where target_type=? and target_id=? and user_id=? and status='pending'", type, id, userId);
    }

    private void notifyCreator(Map<String, Object> task, long operatorId, String next) {
        long creatorId = longValue(task.get("creatorId"));
        if (creatorId == operatorId) return;
        String actorName = String.valueOf(requireUserEntity(operatorId).get("nickname"));
        String action = switch (next) { case "accepted" -> "accepted"; case "rejected" -> "rejected"; case "completed" -> "completed"; default -> "updated"; };
        notificationService.createNotification(creatorId, "task_" + action, "团队任务状态更新",
                actorName + actionLabel(next) + "：" + task.get("title"), "team_task", longValue(task.get("id")), null);
    }

    private void notifyAssignees(long taskId, long operatorId, String type, String title, String content) {
        for (Map<String, Object> assignee : teamTaskAssignees(taskId)) {
            long assigneeUserId = longValue(assignee.get("userId"));
            if (assigneeUserId == operatorId) continue;
            notificationService.createNotification(assigneeUserId, type, title, content, "team_task", taskId, null);
        }
    }

    private static void reorderSubset(List<Long> all, List<Long> requested, String field) {
        if (requested.isEmpty() || new HashSet<>(requested).size() != requested.size() || !new HashSet<>(all).containsAll(requested)) {
            throw new BusinessException(400, field + " contains invalid ids");
        }
        Set<Long> selected = new HashSet<>(requested);
        List<Integer> positions = new ArrayList<>();
        for (int i = 0; i < all.size(); i++) if (selected.contains(all.get(i))) positions.add(i);
        for (int i = 0; i < positions.size(); i++) all.set(positions.get(i), requested.get(i));
    }

    private void createReassignedReminder(long userId, long taskId, Map<String, Object> task) {
        String deadlineTime = String.valueOf(task.getOrDefault("deadlineTime", ""));
        if (!deadlineTime.isBlank()) insertReminder(userId, taskId, parseTime(deadlineTime));
    }

    private void createTeamTaskReminders(long userId, long taskId, Map<String, Object> req) {
        Object arr = req.get("remindAts");
        if (arr instanceof List<?> list) {
            for (Object v : list) insertReminder(userId, taskId, parseTime(String.valueOf(v)));
        } else if (req.get("remindAt") != null) {
            insertReminder(userId, taskId, parseTime(String.valueOf(req.get("remindAt"))));
        }
    }

    private void insertReminder(long userId, long taskId, Timestamp remindAt) {
        if (remindAt != null) {
            jdbc.update("insert into reminder (user_id,target_type,target_id,remind_at,status) values (?,'team_task',?,?, 'pending')", userId, taskId, remindAt);
        }
    }
    private Map<String, Object> requireTeam(long teamId) {
        try {
            return jdbc.queryForObject("select id,name,invite_code inviteCode,invite_code_expire_at inviteCodeExpireAt,owner_id ownerId,status,created_at createdAt from team where id=? and deleted_at is null", (rs, i) -> {
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("id", rs.getLong("id"));
                m.put("name", rs.getString("name"));
                m.put("inviteCode", rs.getString("inviteCode"));
                m.put("inviteCodeExpireAt", iso(rs.getTimestamp("inviteCodeExpireAt")));
                m.put("ownerId", rs.getLong("ownerId"));
                m.put("status", rs.getString("status"));
                m.put("createdAt", iso(rs.getTimestamp("createdAt")));
                return m;
            }, teamId);
        } catch (EmptyResultDataAccessException ex) {
            throw new BusinessException(404, "team not found");
        }
    }

    private Map<String, Object> userView(long userId) {
        Map<String, Object> user = requireUserEntity(userId);
        user.remove("passwordHash");
        return user;
    }

    private Map<String, Object> requireUserEntity(long userId) {
        try {
            return jdbc.queryForObject("select id, phone, password_hash passwordHash, nickname, avatar_url avatarUrl, timezone, status, created_at createdAt from `user` where id = ? and deleted_at is null", userMapper(), userId);
        } catch (EmptyResultDataAccessException ex) {
            throw new BusinessException(404, "user not found");
        }
    }

    private RowMapper<Map<String, Object>> userMapper() {
        return (rs, i) -> {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", rs.getLong("id"));
            m.put("phone", rs.getString("phone"));
            m.put("passwordHash", rs.getString("passwordHash"));
            m.put("nickname", rs.getString("nickname"));
            m.put("avatarUrl", rs.getString("avatarUrl"));
            m.put("timezone", rs.getString("timezone"));
            m.put("status", rs.getString("status"));
            m.put("createdAt", iso(rs.getTimestamp("createdAt")));
            return m;
        };
    }

    private Integer count(String sql, Object... args) {
        Integer n = jdbc.queryForObject(sql, Integer.class, args);
        return n == null ? 0 : n;
    }

    private static String iso(Timestamp ts) { return ts == null ? "" : OffsetDateTime.ofInstant(ts.toInstant(), ZoneOffset.UTC).toString(); }

    private static boolean blank(String s) { return s == null || s.isBlank(); }

    private static long longValue(Object v) { return ((Number) v).longValue(); }

    private static long number(Object v) { return v instanceof Number n ? n.longValue() : Long.parseLong(String.valueOf(v)); }

    private static String text(Map<String, Object> m, String k) { return String.valueOf(m.getOrDefault(k, "")); }

    private static String textOr(Map<String, Object> m, String k, String f) { String v = text(m, k); return blank(v) ? f : v; }

    private static String nullableText(Object v) { if (v == null) return null; String s = String.valueOf(v); return s.isBlank() ? null : s; }

    private static Timestamp parseOptional(Object v) { return v == null ? null : parseTime(String.valueOf(v)); }

    private static Timestamp parseEditableTime(Object value, String field) {
        if (value == null || String.valueOf(value).isBlank()) return null;
        Timestamp parsed = parseTime(String.valueOf(value));
        if (parsed == null) throw new BusinessException(400, field + " is invalid");
        return parsed;
    }

    private static Timestamp parseTime(String value) {
        if (value == null || value.isBlank()) return null;
        try { return Timestamp.from(OffsetDateTime.parse(value).toInstant()); } catch (Exception ignored) {}
        try { return Timestamp.valueOf(LocalDateTime.parse(value)); } catch (Exception ignored) {}
        return null;
    }
}
