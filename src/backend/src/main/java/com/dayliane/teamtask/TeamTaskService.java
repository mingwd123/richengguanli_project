package com.dayliane.teamtask;

import com.dayliane.common.BusinessException;
import com.dayliane.common.PermissionService;
import com.dayliane.fatigue.FatigueService;
import com.dayliane.notification.NotificationService;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
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
    private final FatigueService fatigueService;

    public TeamTaskService(JdbcTemplate jdbc, PermissionService permissionService, NotificationService notificationService, FatigueService fatigueService) {
        this.jdbc = jdbc;
        this.permissionService = permissionService;
        this.notificationService = notificationService;
        this.fatigueService = fatigueService;
    }

    // ===== Public API Methods =====

    @Transactional
    public Map<String, Object> createTeamTask(long userId, Map<String, Object> req) {
        long teamId = number(req.get("teamId"));
        permissionService.requireActiveMember(teamId, userId);
        boolean requiresApproval = !permissionService.canManageTeam(teamId, userId);
        String title = text(req, "title");
        if (title.isBlank()) throw new BusinessException(400, "title is required");
        Object raw = req.get("assigneeUserIds");
        List<?> assigneeIds = raw instanceof List<?> list ? list : List.of();
        if (assigneeIds.isEmpty()) throw new BusinessException(400, "assigneeUserIds is required");
        List<Long> normalizedAssigneeIds = assigneeIds.stream().map(TeamTaskService::number).toList();
        if (new HashSet<>(normalizedAssigneeIds).size() != normalizedAssigneeIds.size()) {
            throw new BusinessException(400, "assigneeUserIds must not contain duplicates");
        }
        for (Long assigneeId : normalizedAssigneeIds) permissionService.requireActiveMember(teamId, assigneeId);
        Timestamp startTime = parseEditableTime(req.get("startTime"), "startTime");
        Timestamp deadlineTime = parseEditableTime(req.get("deadlineTime"), "deadlineTime");
        validateTimeRange(startTime, deadlineTime);
        Map<String, Object> group = resolveTeamTaskGroup(teamId, req);
        int sortOrder = nextTeamTaskSortOrder(teamId, longValue(group.get("id")));
        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbc.update(con -> {
            PreparedStatement ps = con.prepareStatement("insert into team_task (team_id,creator_id,title,description,group_id,group_name,sort_order,start_time,deadline_time,status,approval_status,updated_by) values (?,?,?,?,?,?,?,?,?,?,?,?)", new String[]{"id"});
            ps.setLong(1, teamId);
            ps.setLong(2, userId);
            ps.setString(3, title);
            ps.setString(4, text(req, "description"));
            ps.setLong(5, longValue(group.get("id")));
            ps.setString(6, String.valueOf(group.get("name")));
            ps.setInt(7, sortOrder);
            ps.setTimestamp(8, startTime);
            ps.setTimestamp(9, deadlineTime);
            ps.setString(10, requiresApproval ? "pending_approval" : "active");
            ps.setString(11, requiresApproval ? "pending" : "approved");
            ps.setLong(12, userId);
            return ps;
        }, keyHolder);
        long taskId = Objects.requireNonNull(keyHolder.getKey()).longValue();
        recordEvent(taskId, userId, "created", "创建了任务");
        saveReminderPlan(taskId, req);
        for (Long assigneeUserId : normalizedAssigneeIds) {
            jdbc.update("insert into team_task_assignee (task_id,user_id,assign_round,is_active,status,assigned_by,assigned_at) values (?,?,1,true,'pending',?,utc_timestamp())", taskId, assigneeUserId, userId);
            recordEvent(taskId, userId, requiresApproval ? "assignment_proposed" : "assigned", (requiresApproval ? "提议分配给 " : "分配给 ") + requireUserEntity(assigneeUserId).get("nickname"));
            if (!requiresApproval) activateAssignment(taskId, assigneeUserId, title, true);
        }
        if (requiresApproval) {
            recordEvent(taskId, userId, "approval_requested", "提交团队管理员审批");
            notifyTeamManagers(teamId, userId, taskId, title);
        }
        return teamTaskSummary(taskId, userId);
    }

    public Map<String, Object> listMyTeamTasks(long userId, int page, int size, String assignStatus, String keyword, String dateFrom, String dateTo) {
        return listMyTeamTasks(userId, page, size, assignStatus, keyword, dateFrom, dateTo, "manual");
    }

    public Map<String, Object> listMyTeamTasks(long userId, int page, int size, String assignStatus, String keyword, String dateFrom, String dateTo, String sort) {
        boolean completedHistory = "completed".equals(assignStatus);
        StringBuilder from = new StringBuilder(" from team_task t join team_task_assignee a on a.task_id=t.id join team_member m on m.team_id=t.team_id and m.user_id=? and m.status='active' where a.user_id=?");
        from.append(completedHistory ? " and a.status='completed'" : " and a.is_active=true");
        from.append(" and t.approval_status='approved' and t.deleted_at is null");
        List<Object> params = new ArrayList<>(List.of(userId, userId));
        if (!blank(assignStatus) && !completedHistory) { from.append(" and a.status=?"); params.add(assignStatus); }
        if (!blank(keyword)) { from.append(" and t.title like ?"); params.add("%" + keyword + "%"); }
        appendDateOverlap(from, params, dateFrom, dateTo, userZone(userId));
        String order = "completed".equals(assignStatus) && "manual".equals(sort) ? "t.sort_order asc,t.id asc" : teamTaskOrder(sort, false);
        return queryTaskPage(from.toString(), params, order, userId, page, size);
    }

    public Map<String, Object> listCreatedTeamTasks(long userId, int page, int size, String status, String keyword, String dateFrom, String dateTo) {
        return listCreatedTeamTasks(userId, page, size, status, keyword, dateFrom, dateTo, "manual");
    }

    public Map<String, Object> listCreatedTeamTasks(long userId, int page, int size, String status, String keyword, String dateFrom, String dateTo, String sort) {
        StringBuilder from = new StringBuilder(" from team_task t join team_member m on m.team_id=t.team_id and m.user_id=? and m.status='active' where t.creator_id=? and t.deleted_at is null");
        List<Object> params = new ArrayList<>(List.of(userId, userId));
        if (!blank(status)) { from.append(" and t.status=?"); params.add(status); }
        if (!blank(keyword)) { from.append(" and t.title like ?"); params.add("%" + keyword + "%"); }
        appendDateOverlap(from, params, dateFrom, dateTo, userZone(userId));
        String order = "completed".equals(status) && "manual".equals(sort) ? "t.sort_order asc,t.id asc" : teamTaskOrder(sort, true);
        return queryTaskPage(from.toString(), params, order, userId, page, size);
    }

    public Map<String, Object> listTeamTasks(long teamId, long userId, int page, int size, String status, String keyword, String dateFrom, String dateTo) {
        return listTeamTasks(teamId, userId, page, size, status, keyword, dateFrom, dateTo, "manual");
    }

    public Map<String, Object> listTeamTasks(long teamId, long userId, int page, int size, String status, String keyword, String dateFrom, String dateTo, String sort) {
        permissionService.requireActiveMember(teamId, userId);
        StringBuilder from = new StringBuilder(" from team_task t where t.team_id=? and t.deleted_at is null");
        List<Object> params = new ArrayList<>();
        params.add(teamId);
        if (!permissionService.canManageTeam(teamId, userId)) {
            from.append(" and (t.approval_status='approved' or t.creator_id=?)");
            params.add(userId);
        }
        if (!blank(status)) { from.append(" and t.status=?"); params.add(status); }
        if (!blank(keyword)) { from.append(" and t.title like ?"); params.add("%" + keyword + "%"); }
        appendDateOverlap(from, params, dateFrom, dateTo, userZone(userId));
        String order = "completed".equals(status) && "manual".equals(sort) ? "t.sort_order asc,t.id asc" : teamTaskOrder(sort, true);
        return queryTaskPage(from.toString(), params, order, userId, page, size);
    }

    public List<Map<String, Object>> listMyTeamTasksInRange(long userId, Instant startInclusive, Instant endExclusive) {
        String sql = "select t.id from team_task t join team_task_assignee a on a.task_id=t.id " +
                "join team_member m on m.team_id=t.team_id and m.user_id=? and m.status='active' " +
                "where a.user_id=? and a.is_active=true and a.status in ('pending','accepted') and t.approval_status='approved' and t.status in ('active','unassigned') and t.deleted_at is null " +
                "and coalesce(t.deadline_time,t.start_time,t.created_at)>=? and coalesce(t.start_time,t.deadline_time,t.created_at)<? " +
                "order by coalesce(t.deadline_time,t.start_time,t.created_at),t.id";
        List<Long> ids = jdbc.queryForList(sql, Long.class, userId, userId, Timestamp.from(startInclusive), Timestamp.from(endExclusive));
        return ids.stream().map(id -> teamTaskSummary(id, userId)).toList();
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
        boolean teamManager = permissionService.canManageTeam(teamId, userId);
        if (!"approved".equals(task.get("approvalStatus")) && longValue(task.get("creatorId")) != userId && !teamManager) {
            throw new BusinessException(403, "task is awaiting manager review");
        }
        Map<String, Object> creator = userView(longValue(task.get("creatorId")));
        boolean exposeCompletedFatigue = fatigueService.trackingEnabledFor(userId);
        task.put("teamName", requireTeam(teamId).get("name"));
        task.put("assignees", teamTaskAssignees(taskId, userId, exposeCompletedFatigue));
        task.put("reassignmentCandidates", teamTaskReassignmentCandidates(taskId));
        task.put("events", teamTaskEvents(taskId));
        task.put("creator", creator);
        task.put("creatorName", creator.get("nickname"));
        task.put("canManage", longValue(task.get("creatorId")) == userId || teamManager);
        task.put("canReview", teamManager && "pending".equals(task.get("approvalStatus")));
        task.put("pendingReminders", jdbc.query("select id,user_id userId,remind_at remindAt,status from reminder where target_type='team_task' and target_id=? and status='pending' order by remind_at,user_id", (rs, i) -> Map.of(
                "id", rs.getLong("id"), "userId", rs.getLong("userId"), "remindAt", iso(rs.getTimestamp("remindAt")), "status", rs.getString("status")), taskId));
        Map<String, Object> mine = findMyAssignee(taskId, userId);
        if (mine != null) {
            task.put("assigneeId", mine.get("id"));
            task.put("assignStatus", mine.get("status"));
            task.put("assignRound", mine.get("assignRound"));
            task.put("completedAt", mine.get("completedAt") == null ? null : iso((Timestamp) mine.get("completedAt")));
            if (exposeCompletedFatigue) {
                task.put("completedFatigueLevel", mine.get("completedFatigueLevel"));
                task.put("completedFatigueWeight", mine.get("completedFatigueWeight"));
            }
        }
        return task;
    }

    @Transactional
    public Map<String, Object> approveTeamTask(long taskId, long userId) {
        Map<String, Object> task = requireTeamTaskForUpdate(taskId);
        long teamId = longValue(task.get("teamId"));
        permissionService.requireTeamManager(teamId, userId);
        requirePendingApproval(task);
        if (((Number) task.get("unassignedCount")).intValue() > 0) {
            throw new BusinessException(400, "all removed assignees must be reassigned before approval");
        }
        int activeAssignees = count("select count(*) from team_task_assignee where task_id=? and is_active=true", taskId);
        int activeTeamMembers = count("select count(*) from team_task_assignee a join team_member m on m.team_id=? and m.user_id=a.user_id and m.status='active' where a.task_id=? and a.is_active=true", teamId, taskId);
        if (activeAssignees == 0 || activeAssignees != activeTeamMembers) {
            throw new BusinessException(400, "task has no complete set of active assignees");
        }
        int updated = jdbc.update("update team_task set approval_status='approved',status='active',reviewed_by=?,reviewed_at=utc_timestamp(),updated_by=? where id=? and approval_status='pending' and status='pending_approval'", userId, userId, taskId);
        if (updated == 0) throw new BusinessException(409, "task approval state has changed");
        recalculateTeamTaskStatus(taskId);
        for (Long assigneeId : openAssigneeUserIds(taskId)) activateAssignment(taskId, assigneeId, String.valueOf(task.get("title")), false);
        recordEvent(taskId, userId, "approved", "团队管理员批准了任务安排");
        notifyApprovalResult(task, userId, true);
        return teamTaskDetail(taskId, userId);
    }

    @Transactional
    public Map<String, Object> rejectTeamTaskApproval(long taskId, long userId) {
        Map<String, Object> task = requireTeamTaskForUpdate(taskId);
        permissionService.requireTeamManager(longValue(task.get("teamId")), userId);
        requirePendingApproval(task);
        int updated = jdbc.update("update team_task set approval_status='rejected',status='approval_rejected',reviewed_by=?,reviewed_at=utc_timestamp(),updated_by=? where id=? and approval_status='pending' and status='pending_approval'", userId, userId, taskId);
        if (updated == 0) throw new BusinessException(409, "task approval state has changed");
        cancelActiveReminders(taskId, null);
        recordEvent(taskId, userId, "approval_rejected", "团队管理员拒绝了任务安排");
        notifyApprovalResult(task, userId, false);
        return teamTaskDetail(taskId, userId);
    }

    @Transactional
    public Map<String, Object> resubmitTeamTaskApproval(long taskId, long userId) {
        Map<String, Object> task = requireManagedTeamTaskForUpdate(taskId, userId);
        if (!"rejected".equals(task.get("approvalStatus")) || !"approval_rejected".equals(task.get("status"))) {
            throw new BusinessException(400, "task approval cannot be resubmitted");
        }
        int updated = jdbc.update("update team_task set approval_status='pending',status='pending_approval',reviewed_by=null,reviewed_at=null,updated_by=? where id=? and approval_status='rejected' and status='approval_rejected'", userId, taskId);
        if (updated == 0) throw new BusinessException(409, "task approval state has changed");
        recordEvent(taskId, userId, "approval_resubmitted", "重新提交了团队任务审批");
        notifyTeamManagers(longValue(task.get("teamId")), userId, taskId, String.valueOf(task.get("title")));
        return teamTaskDetail(taskId, userId);
    }

    @Transactional
    public Map<String, Object> teamTaskAssigneeTransition(long taskId, long userId, String next, List<String> allowedFrom) {
        if ("completed".equals(next)) throw new BusinessException(400, "use the dedicated completion flow to record completion fatigue");
        Map<String, Object> task = requireTeamTaskForUpdate(taskId);
        if (!"approved".equals(task.get("approvalStatus"))) throw new BusinessException(400, "task assignment is not approved");
        if (!List.of("active", "unassigned").contains(String.valueOf(task.get("status")))) throw new BusinessException(400, "task cannot be operated in current status");
        Map<String, Object> a = requireMyActiveAssigneeForUpdate(taskId, userId);
        String current = String.valueOf(a.get("status"));
        if (!allowedFrom.contains(current)) throw new BusinessException(400, "invalid status transition");
        String transitionColumns = switch (next) {
            case "accepted" -> "accepted_at=utc_timestamp(), rejected_at=null, completed_at=null, completed_fatigue_level=null, completed_fatigue_weight=null";
            case "rejected" -> "rejected_at=utc_timestamp(), accepted_at=null, completed_at=null, completed_fatigue_level=null, completed_fatigue_weight=null";
            case "completed" -> "completed_at=utc_timestamp()";
            default -> "status_updated_at=utc_timestamp()";
        };
        int updated = jdbc.update("update team_task_assignee set status=?, " + transitionColumns + ", status_updated_by=?, status_updated_at=utc_timestamp() where id=? and status=? and is_active=true", next, userId, a.get("id"), current);
        if (updated == 0) throw new BusinessException(409, "assignee status has changed");
        recordEvent(taskId, userId, next, actionLabel(next));
        recalculateTeamTaskStatus(taskId);
        notifyCreator(task, userId, next);
        if (List.of("rejected", "completed").contains(next)) {
            cancelActiveReminders(taskId, userId);
        }
        if ("completed".equals(String.valueOf(requireTeamTask(taskId).get("status")))) {
            cancelActiveReminders(taskId, null);
        }
        return teamTaskDetail(taskId, userId);
    }

    @Transactional
    public Map<String, Object> completeTeamTask(long taskId, long userId, Map<String, Object> req) {
        Map<String, Object> task = requireTeamTaskForUpdate(taskId);
        if (!"approved".equals(task.get("approvalStatus"))) throw new BusinessException(400, "task assignment is not approved");
        Map<String, Object> a = requireMyActiveAssigneeForUpdate(taskId, userId);
        String current = String.valueOf(a.get("status"));
        if ("completed".equals(current)) {
            return teamTaskDetail(taskId, userId);
        }
        if (!"accepted".equals(current)) throw new BusinessException(400, "invalid status transition");
        if (!List.of("active", "unassigned").contains(String.valueOf(task.get("status")))) throw new BusinessException(400, "task cannot be operated in current status");

        boolean trackingEnabled = fatigueService.trackingEnabledFor(userId);
        Integer fatigueLevel = null;
        BigDecimal weight = null;
        if (trackingEnabled) {
            fatigueLevel = parseFatigueLevel(req);
            weight = fatigueService.currentWeight(userId, fatigueLevel);
        }

        int updated = trackingEnabled
                ? jdbc.update("update team_task_assignee set status='completed', completed_at=utc_timestamp(), completed_fatigue_level=?, completed_fatigue_weight=?, status_updated_by=?, status_updated_at=utc_timestamp() where id=? and status='accepted' and is_active=true", fatigueLevel, weight, userId, a.get("id"))
                : jdbc.update("update team_task_assignee set status='completed', completed_at=utc_timestamp(), completed_fatigue_level=null, completed_fatigue_weight=null, status_updated_by=?, status_updated_at=utc_timestamp() where id=? and status='accepted' and is_active=true", userId, a.get("id"));
        if (updated == 0) throw new BusinessException(409, "assignee status has changed");

        if (trackingEnabled) fatigueService.touchDataRevision(userId);
        recordEvent(taskId, userId, "completed", "完成了任务");
        recalculateTeamTaskStatus(taskId);
        notifyCreator(task, userId, "completed");
        cancelActiveReminders(taskId, userId);
        if ("completed".equals(String.valueOf(requireTeamTask(taskId).get("status")))) {
            cancelActiveReminders(taskId, null);
        }
        return teamTaskDetail(taskId, userId);
    }

    private static Integer parseFatigueLevel(Map<String, Object> req) {
        Object raw = req == null ? null : req.get("fatigueLevel");
        if (raw == null) throw new BusinessException(400, "fatigueLevel is required when fatigue tracking is enabled");
        if (!(raw instanceof Number number)) throw new BusinessException(400, "fatigueLevel must be an integer");
        if (number.doubleValue() != Math.floor(number.doubleValue())) {
            throw new BusinessException(400, "fatigueLevel must be an integer");
        }
        int level = number.intValue();
        if (level < 1 || level > 5) throw new BusinessException(400, "fatigueLevel must be between 1 and 5");
        return level;
    }

    @Transactional
    public Map<String, Object> updateTeamTask(long taskId, long userId, Map<String, Object> req) {
        Map<String, Object> task = requireManagedTeamTaskForUpdate(taskId, userId);
        boolean hasTitle = req.containsKey("title");
        String title = hasTitle ? text(req, "title").trim() : null;
        if (hasTitle && title.isBlank()) throw new BusinessException(400, "title is required");
        boolean hasDescription = req.containsKey("description");
        String description = hasDescription ? text(req, "description") : null;
        boolean hasStartTime = req.containsKey("startTime");
        boolean hasDeadlineTime = req.containsKey("deadlineTime");
        Timestamp startTime = parseEditableTime(req.get("startTime"), "startTime");
        Timestamp deadlineTime = parseEditableTime(req.get("deadlineTime"), "deadlineTime");
        Timestamp nextStartTime = hasStartTime ? startTime : parseTime(String.valueOf(task.getOrDefault("startTime", "")));
        Timestamp nextDeadlineTime = hasDeadlineTime ? deadlineTime : parseTime(String.valueOf(task.getOrDefault("deadlineTime", "")));
        validateTimeRange(nextStartTime, nextDeadlineTime);
        Map<String, Object> group = req.containsKey("groupId") || req.containsKey("groupName") ? resolveTeamTaskGroup(longValue(task.get("teamId")), req) : null;
        Integer sortOrder = group == null ? null : nextTeamTaskSortOrder(longValue(task.get("teamId")), longValue(group.get("id")));
        jdbc.update("update team_task set title=case when ? then ? else title end,description=case when ? then ? else description end,group_id=coalesce(?,group_id),group_name=coalesce(?,group_name),sort_order=coalesce(?,sort_order),start_time=case when ? then ? else start_time end,deadline_time=case when ? then ? else deadline_time end,time_updated_at=case when ? then utc_timestamp() else time_updated_at end,updated_by=? where id=? and deleted_at is null",
                hasTitle, title, hasDescription, description, group == null ? null : longValue(group.get("id")), group == null ? null : String.valueOf(group.get("name")), sortOrder,
                hasStartTime, startTime, hasDeadlineTime, deadlineTime, hasStartTime || hasDeadlineTime, userId, taskId);
        if (req.containsKey("remindAt") || req.containsKey("remindAts")) {
            cancelActiveReminders(taskId, null);
            replaceReminderPlan(taskId, req);
            Map<String, Object> refreshed = requireTeamTask(taskId);
            if ("approved".equals(refreshed.get("approvalStatus")) && List.of("active", "unassigned").contains(String.valueOf(refreshed.get("status")))) {
                for (Long assigneeId : openAssigneeUserIds(taskId)) instantiateReminderPlan(taskId, assigneeId, true);
            }
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
        Map<String, Object> task = requireTeamTask(taskId);
        boolean hasStartTime = req.containsKey("startTime");
        boolean hasDeadlineTime = req.containsKey("deadlineTime");
        if (!hasStartTime && !hasDeadlineTime) throw new BusinessException(400, "startTime or deadlineTime is required");
        Timestamp startTime = hasStartTime ? parseEditableTime(req.get("startTime"), "startTime") : parseTime(String.valueOf(task.getOrDefault("startTime", "")));
        Timestamp deadlineTime = hasDeadlineTime ? parseEditableTime(req.get("deadlineTime"), "deadlineTime") : parseTime(String.valueOf(task.getOrDefault("deadlineTime", "")));
        validateTimeRange(startTime, deadlineTime);
        jdbc.update("update team_task set start_time=case when ? then ? else start_time end,deadline_time=case when ? then ? else deadline_time end,time_updated_at=utc_timestamp(),updated_by=? where id=? and deleted_at is null",
                hasStartTime, startTime, hasDeadlineTime, deadlineTime, userId, taskId);
        recordEvent(taskId, userId, "time_updated", "更新了任务时间");
        return teamTaskDetail(taskId, userId);
    }

    @Transactional
    public void deleteTeamTask(long taskId, long userId) {
        Map<String, Object> task = requireManagedTeamTaskForUpdate(taskId, userId);
        if ("completed".equals(task.get("status"))) throw new BusinessException(400, "completed task cannot be deleted");
        int updated = jdbc.update("update team_task set deleted_at=utc_timestamp(), deleted_by=? where id=? and status<>'completed' and deleted_at is null", userId, taskId);
        if (updated == 0) throw new BusinessException(409, "task status has changed");
        cancelActiveReminders(taskId, null);
        recordEvent(taskId, userId, "deleted", "删除了任务");
    }

    @Transactional
    public Map<String, Object> cancelTeamTask(long taskId, long userId) {
        Map<String, Object> task = requireManagedTeamTaskForUpdate(taskId, userId);
        String currentStatus = String.valueOf(task.get("status"));
        if (!List.of("pending_approval", "active", "unassigned", "all_rejected").contains(currentStatus)) {
            throw new BusinessException(400, "task cannot be cancelled in current status");
        }
        int updated = jdbc.update("update team_task set status='cancelled', updated_by=? where id=? and status=?", userId, taskId, currentStatus);
        if (updated == 0) throw new BusinessException(409, "task status has changed");
        recordEvent(taskId, userId, "cancelled", "取消了任务");
        pausePendingReminders(taskId);
        if ("pending_approval".equals(currentStatus)) {
            notifyApprovalWithdrawal(task, userId);
        } else if ("approved".equals(task.get("approvalStatus"))) {
            notifyAssignees(taskId, userId, "task_cancelled", "团队任务已取消", "任务已取消：" + task.get("title"));
        }
        return teamTaskSummary(taskId, userId);
    }

    @Transactional
    public Map<String, Object> restoreTeamTask(long taskId, long userId) {
        Map<String, Object> task = requireTeamTaskForUpdate(taskId);
        if (!permissionService.canManageTeam(longValue(task.get("teamId")), userId)) throw new BusinessException(403, "only team manager can restore task");
        if (!"cancelled".equals(task.get("status"))) throw new BusinessException(400, "only cancelled task can be restored");
        String provisionalStatus = switch (String.valueOf(task.get("approvalStatus"))) {
            case "pending" -> "pending_approval";
            case "rejected" -> "approval_rejected";
            default -> "active";
        };
        int updated = jdbc.update("update team_task set status=?, updated_by=? where id=? and status='cancelled'", provisionalStatus, userId, taskId);
        if (updated == 0) throw new BusinessException(409, "task status has changed");
        recordEvent(taskId, userId, "restored", "恢复了任务");
        recalculateTeamTaskStatus(taskId);
        Map<String, Object> restored = requireTeamTask(taskId);
        if ("approved".equals(restored.get("approvalStatus")) && List.of("active", "unassigned").contains(String.valueOf(restored.get("status")))) {
            resumePausedReminders(taskId);
            for (Long assigneeId : openAssigneeUserIds(taskId)) instantiateReminderPlan(taskId, assigneeId);
        }
        return teamTaskSummary(taskId, userId);
    }

    @Transactional
    public Map<String, Object> reassignTeamTask(long taskId, long userId, long originalUserId, long newUserId) {
        Map<String, Object> task = requireManagedTeamTaskForUpdate(taskId, userId);
        if (List.of("completed", "cancelled", "approval_rejected").contains(String.valueOf(task.get("status")))) {
            throw new BusinessException(400, "task cannot be reassigned in current status");
        }
        long teamId = longValue(task.get("teamId"));
        permissionService.requireActiveMember(teamId, newUserId);
        if (count("select count(*) from team_task_assignee where task_id=? and user_id=? and is_active=true", taskId, newUserId) > 0) {
            throw new BusinessException(409, "new user is already an active assignee");
        }
        Map<String, Object> old;
        try {
            old = jdbc.queryForObject("select id,assign_round assignRound,status,is_active isActive from team_task_assignee where task_id=? and user_id=? order by id desc limit 1 for update", (rs, i) -> Map.of("id", rs.getLong("id"), "assignRound", rs.getInt("assignRound"), "status", rs.getString("status"), "isActive", rs.getBoolean("isActive")), taskId, originalUserId);
        } catch (EmptyResultDataAccessException ex) {
            throw new BusinessException(404, "original assignee not found");
        }
        boolean oldActive = Boolean.TRUE.equals(old.get("isActive"));
        if (oldActive && !"rejected".equals(old.get("status"))) throw new BusinessException(400, "only rejected or removed assignee can be reassigned");
        if (!oldActive && count("select count(*) from team_task_assignee where task_id=? and reassigned_from_user_id=? and assign_round>?", taskId, originalUserId, old.get("assignRound")) > 0) {
            throw new BusinessException(400, "original assignment has already been replaced");
        }
        if (oldActive) {
            int deactivated = jdbc.update("update team_task_assignee set is_active=false where id=? and is_active=true", old.get("id"));
            if (deactivated == 0) throw new BusinessException(409, "original assignment has changed");
        }
        Integer nextRound = jdbc.queryForObject("select coalesce(max(assign_round),0)+1 from team_task_assignee where task_id=?", Integer.class, taskId);
        try {
            jdbc.update("insert into team_task_assignee (task_id,user_id,assign_round,is_active,status,reassigned_from_user_id,assigned_by,assigned_at) values (?,?,?,true,'pending',?,?,utc_timestamp())",
                    taskId, newUserId, nextRound == null ? 1 : nextRound, originalUserId, userId);
        } catch (DataIntegrityViolationException ex) {
            throw new BusinessException(409, "new user is already an active assignee");
        }
        if (!oldActive) jdbc.update("update team_task set unassigned_count=greatest(unassigned_count-1,0) where id=?", taskId);
        if ("approved".equals(task.get("approvalStatus"))) {
            activateAssignment(taskId, newUserId, String.valueOf(task.get("title")), false);
            ensureLegacyDeadlineReminder(taskId, newUserId, task);
        }
        recordEvent(taskId, userId, "reassigned", "将任务从 " + requireUserEntity(originalUserId).get("nickname") + " 重新分配给 " + requireUserEntity(newUserId).get("nickname"));
        recalculateTeamTaskStatus(taskId);
        return teamTaskDetail(taskId, userId);
    }

    @Transactional
    public Map<String, Object> correctTeamTaskAssigneeStatus(long taskId, long assigneeId, long userId, String status) {
        Map<String, Object> task = requireManagedTeamTaskForUpdate(taskId, userId);
        if (!"approved".equals(task.get("approvalStatus"))) throw new BusinessException(400, "task assignment is not approved");
        if ("completed".equals(task.get("status"))) throw new BusinessException(400, "completed task cannot be reopened");
        if (!List.of("pending", "accepted", "rejected").contains(status)) throw new BusinessException(400, "status is invalid");
        Long correctedUserId;
        try {
            correctedUserId = jdbc.queryForObject("select user_id from team_task_assignee where id=? and task_id=? and is_active=true", Long.class, assigneeId, taskId);
        } catch (EmptyResultDataAccessException ex) {
            throw new BusinessException(404, "assignee not found");
        }
        String timeColumn = "accepted".equals(status) ? "accepted_at" : "rejected".equals(status) ? "rejected_at" : null;
        int updated;
        if (timeColumn == null) {
            updated = jdbc.update("update team_task_assignee set status=?, completed_at=null, completed_fatigue_level=null, completed_fatigue_weight=null, accepted_at=null, rejected_at=null, status_updated_by=?, status_updated_at=utc_timestamp() where id=? and task_id=? and is_active=true", status, userId, assigneeId, taskId);
        } else if ("accepted_at".equals(timeColumn)) {
            updated = jdbc.update("update team_task_assignee set status=?, completed_at=null, completed_fatigue_level=null, completed_fatigue_weight=null, rejected_at=null, accepted_at=utc_timestamp(), status_updated_by=?, status_updated_at=utc_timestamp() where id=? and task_id=? and is_active=true", status, userId, assigneeId, taskId);
        } else {
            updated = jdbc.update("update team_task_assignee set status=?, completed_at=null, completed_fatigue_level=null, completed_fatigue_weight=null, accepted_at=null, rejected_at=utc_timestamp(), status_updated_by=?, status_updated_at=utc_timestamp() where id=? and task_id=? and is_active=true", status, userId, assigneeId, taskId);
        }
        if (updated == 0) throw new BusinessException(409, "assignee not found or state has changed");
        fatigueService.touchDataRevision(correctedUserId);
        recordEvent(taskId, userId, "status_corrected", "修正执行人状态为: " + status);
        recalculateTeamTaskStatus(taskId);
        syncRemindersAfterAssigneeCorrection(taskId, assigneeId, status);
        return teamTaskDetail(taskId, userId);
    }

    @Transactional
    public void deactivateMemberAssignments(long teamId, long targetUserId, long operatorId) {
        List<Map<String, Object>> assignments = jdbc.query("select a.id,a.task_id taskId,a.status,t.team_id teamId,t.creator_id creatorId,t.title,t.approval_status approvalStatus from team_task_assignee a join team_task t on t.id=a.task_id where t.team_id=? and t.deleted_at is null and t.status<>'completed' and a.user_id=? and a.is_active=true and a.status<>'completed' order by a.task_id,a.id", (rs, i) -> Map.of(
                "id", rs.getLong("id"), "taskId", rs.getLong("taskId"), "status", rs.getString("status"), "teamId", rs.getLong("teamId"), "creatorId", rs.getLong("creatorId"), "title", rs.getString("title"), "approvalStatus", rs.getString("approvalStatus")), teamId, targetUserId);
        for (Map<String, Object> assignment : assignments) {
            long taskId = longValue(assignment.get("taskId"));
            Map<String, Object> task;
            try {
                task = requireTeamTaskForUpdate(taskId);
            } catch (BusinessException ex) {
                if (ex.getCode() == 404) continue;
                throw ex;
            }
            if ("completed".equals(task.get("status"))) continue;
            Map<String, Object> currentAssignment;
            try {
                currentAssignment = jdbc.queryForObject("select status from team_task_assignee where id=? and task_id=? and is_active=true and status<>'completed' for update", (rs, i) -> Map.of("status", rs.getString("status")), assignment.get("id"), taskId);
            } catch (EmptyResultDataAccessException ex) {
                continue;
            }
            String currentStatus = String.valueOf(currentAssignment.get("status"));
            int updated = jdbc.update("update team_task_assignee set is_active=false where id=? and task_id=? and status=? and is_active=true", assignment.get("id"), taskId, currentStatus);
            if (updated == 0) continue;
            boolean requiresReplacement = List.of("pending", "accepted").contains(currentStatus);
            if (requiresReplacement) jdbc.update("update team_task set unassigned_count=unassigned_count+1 where id=?", taskId);
            cancelActiveReminders(taskId, targetUserId);
            recordEvent(taskId, operatorId, "assignee_removed", requiresReplacement ? "执行人被移出团队，任务等待重新分配" : "已拒绝的执行人被移出团队");
            recalculateTeamTaskStatus(taskId);
            long creatorId = longValue(task.get("creatorId"));
            if (requiresReplacement && "pending".equals(task.get("approvalStatus"))) {
                notifyTeamManagersOfVacancy(longValue(task.get("teamId")), operatorId, taskId, String.valueOf(task.get("title")));
            } else if (requiresReplacement && creatorId != operatorId) {
                notificationService.createNotification(creatorId, "task_unassigned", "团队任务需要重新分配",
                        "有执行人已离开团队：" + task.get("title"), "team_task", taskId, null);
            }
        }
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

    private Map<String, Object> requireManagedTeamTaskForUpdate(long taskId, long userId) {
        Map<String, Object> task = requireTeamTaskForUpdate(taskId);
        long teamId = longValue(task.get("teamId"));
        permissionService.requireActiveMember(teamId, userId);
        if (longValue(task.get("creatorId")) != userId && !permissionService.canManageTeam(teamId, userId)) {
            throw new BusinessException(403, "no permission to manage task");
        }
        return task;
    }

    private Map<String, Object> requireTeamTask(long taskId) {
        try {
            return jdbc.queryForObject("select id,team_id teamId,creator_id creatorId,title,description,group_id groupId,group_name groupName,sort_order sortOrder,start_time startTime,deadline_time deadlineTime,status,approval_status approvalStatus,reviewed_by reviewedBy,reviewed_at reviewedAt,unassigned_count unassignedCount,created_at createdAt from team_task where id=? and deleted_at is null", teamTaskMapper(), taskId);
        } catch (EmptyResultDataAccessException ex) {
            throw new BusinessException(404, "team task not found");
        }
    }

    private Map<String, Object> requireTeamTaskForUpdate(long taskId) {
        try {
            return jdbc.queryForObject("select id,team_id teamId,creator_id creatorId,title,description,group_id groupId,group_name groupName,sort_order sortOrder,start_time startTime,deadline_time deadlineTime,status,approval_status approvalStatus,reviewed_by reviewedBy,reviewed_at reviewedAt,unassigned_count unassignedCount,created_at createdAt from team_task where id=? and deleted_at is null for update", teamTaskMapper(), taskId);
        } catch (EmptyResultDataAccessException ex) {
            throw new BusinessException(404, "team task not found");
        }
    }

    private Map<String, Object> teamTaskSummary(long taskId, long userId) {
        Map<String, Object> task = requireTeamTask(taskId);
        boolean exposeCompletedFatigue = fatigueService.trackingEnabledFor(userId);
        task.put("teamName", requireTeam(longValue(task.get("teamId"))).get("name"));
        task.put("assigneeCount", count("select count(*) from team_task_assignee where task_id=? and is_active=true", taskId));
        task.put("assignees", teamTaskAssignees(taskId, userId, exposeCompletedFatigue));
        task.put("reassignmentCandidates", teamTaskReassignmentCandidates(taskId));
        List<Timestamp> reminders = jdbc.query("select min(remind_at) remindAt from reminder where target_type='team_task' and target_id=? and user_id=? and status='pending'", (rs, i) -> rs.getTimestamp("remindAt"), taskId, userId);
        task.put("remindAt", reminders.isEmpty() ? "" : iso(reminders.get(0)));
        Map<String, Object> mine = findMyAssignee(taskId, userId);
        if (mine != null) {
            task.put("assigneeId", mine.get("id"));
            task.put("assignStatus", mine.get("status"));
            task.put("assignRound", mine.get("assignRound"));
            task.put("completedAt", mine.get("completedAt") == null ? null : iso((Timestamp) mine.get("completedAt")));
            if (exposeCompletedFatigue) {
                task.put("completedFatigueLevel", mine.get("completedFatigueLevel"));
                task.put("completedFatigueWeight", mine.get("completedFatigueWeight"));
            }
        }
        return task;
    }

    private List<Map<String, Object>> teamTaskAssignees(long taskId) {
        return teamTaskAssignees(taskId, null, false);
    }

    private List<Map<String, Object>> teamTaskAssignees(long taskId, Long viewerUserId, boolean exposeCompletedFatigue) {
        return jdbc.query("select a.id,a.user_id userId,a.assign_round assignRound,a.is_active isActive,a.status,a.completed_at completedAt,a.completed_fatigue_level completedFatigueLevel,a.completed_fatigue_weight completedFatigueWeight,u.nickname,u.avatar_url avatarUrl from team_task_assignee a join `user` u on u.id=a.user_id where a.task_id=? and (a.is_active=true or a.status='completed') order by a.is_active desc, a.id", (rs, i) -> {
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
            if (viewerUserId != null && rs.getLong("userId") == viewerUserId) {
                Timestamp completedAt = rs.getTimestamp("completedAt");
                m.put("completedAt", completedAt == null ? null : iso(completedAt));
                if (exposeCompletedFatigue) {
                    m.put("completedFatigueLevel", rs.getObject("completedFatigueLevel"));
                    m.put("completedFatigueWeight", rs.getBigDecimal("completedFatigueWeight"));
                }
            }
            return m;
        }, taskId);
    }

    private Map<String, Object> findMyAssignee(long taskId, long userId) {
        List<Map<String, Object>> rows = jdbc.query(
                "select id,user_id userId,status,assign_round assignRound,is_active isActive,completed_at completedAt,completed_fatigue_level completedFatigueLevel,completed_fatigue_weight completedFatigueWeight " +
                        "from team_task_assignee where task_id=? and user_id=? order by is_active desc, assign_round desc, id desc limit 1",
                (rs, i) -> {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("id", rs.getLong("id"));
                    m.put("userId", rs.getLong("userId"));
                    m.put("status", rs.getString("status"));
                    m.put("assignRound", rs.getInt("assignRound"));
                    m.put("isActive", rs.getBoolean("isActive"));
                    m.put("completedAt", rs.getTimestamp("completedAt"));
                    m.put("completedFatigueLevel", rs.getObject("completedFatigueLevel"));
                    m.put("completedFatigueWeight", rs.getBigDecimal("completedFatigueWeight"));
                    return m;
                }, taskId, userId);
        return rows.isEmpty() ? null : rows.get(0);
    }

    private List<Map<String, Object>> teamTaskReassignmentCandidates(long taskId) {
        return jdbc.query("select a.id assigneeId,a.user_id userId,a.status,u.nickname from team_task_assignee a join `user` u on u.id=a.user_id where a.task_id=? and a.is_active=false and a.status<>'completed' and not exists (select 1 from team_task_assignee replacement where replacement.task_id=a.task_id and ((replacement.reassigned_from_user_id=a.user_id and replacement.assign_round>a.assign_round) or (replacement.user_id=a.user_id and replacement.is_active=true))) order by a.id", (rs, i) -> {
            Map<String, Object> candidate = new LinkedHashMap<>();
            candidate.put("assigneeId", rs.getLong("assigneeId"));
            candidate.put("userId", rs.getLong("userId"));
            candidate.put("status", rs.getString("status"));
            candidate.put("nickname", rs.getString("nickname"));
            return candidate;
        }, taskId);
    }

    private Map<String, Object> requireMyActiveAssignee(long taskId, long userId) {
        try {
            return jdbc.queryForObject("select id,user_id userId,status,assign_round assignRound,completed_at completedAt,completed_fatigue_level completedFatigueLevel,completed_fatigue_weight completedFatigueWeight from team_task_assignee where task_id=? and user_id=? and is_active=true", (rs, i) -> {
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("id", rs.getLong("id"));
                m.put("userId", rs.getLong("userId"));
                m.put("status", rs.getString("status"));
                m.put("assignRound", rs.getInt("assignRound"));
                m.put("completedAt", rs.getTimestamp("completedAt"));
                m.put("completedFatigueLevel", rs.getObject("completedFatigueLevel"));
                m.put("completedFatigueWeight", rs.getObject("completedFatigueWeight"));
                return m;
            }, taskId, userId);
        } catch (EmptyResultDataAccessException ex) {
            throw new BusinessException(403, "not task assignee");
        }
    }

    private Map<String, Object> requireMyActiveAssigneeForUpdate(long taskId, long userId) {
        try {
            return jdbc.queryForObject("select id,user_id userId,status,assign_round assignRound from team_task_assignee where task_id=? and user_id=? and is_active=true for update", (rs, i) -> Map.of("id", rs.getLong("id"), "userId", rs.getLong("userId"), "status", rs.getString("status"), "assignRound", rs.getInt("assignRound")), taskId, userId);
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
            m.put("approvalStatus", rs.getString("approvalStatus"));
            m.put("reviewedBy", rs.getObject("reviewedBy"));
            m.put("reviewedAt", iso(rs.getTimestamp("reviewedAt")));
            m.put("unassignedCount", rs.getInt("unassignedCount"));
            m.put("createdAt", iso(rs.getTimestamp("createdAt")));
            return m;
        };
    }

    private List<Map<String, Object>> teamTaskEvents(long taskId) {
        return jdbc.query("select e.id,e.actor_type actorType,e.event_type eventType,e.content,e.created_at createdAt,u.nickname userActorName,ad.username adminActorName from team_task_event e left join `user` u on e.actor_type='user' and u.id=e.actor_id left join admin_user ad on e.actor_type='admin' and ad.id=e.actor_id where e.task_id=? order by e.created_at desc,e.id desc", (rs, i) -> {
            Map<String, Object> event = new LinkedHashMap<>();
            event.put("id", rs.getLong("id"));
            event.put("actorType", rs.getString("actorType"));
            event.put("eventType", rs.getString("eventType"));
            event.put("content", rs.getString("content"));
            event.put("createdAt", iso(rs.getTimestamp("createdAt")));
            String actorName = "admin".equals(rs.getString("actorType")) ? rs.getString("adminActorName") : rs.getString("userActorName");
            event.put("actorName", actorName == null ? "System" : actorName);
            return event;
        }, taskId);
    }

    private void recordEvent(long taskId, long actorId, String eventType, String content) {
        jdbc.update("insert into team_task_event (task_id,actor_id,actor_type,event_type,content) values (?,?,'user',?,?)", taskId, actorId, eventType, content);
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
        Map<String, Object> task = jdbc.queryForMap("select status,approval_status approvalStatus,unassigned_count unassignedCount from team_task where id=? for update", taskId);
        if (List.of("completed", "cancelled").contains(String.valueOf(task.get("status")))) return;
        if ("pending".equals(task.get("approvalStatus"))) {
            jdbc.update("update team_task set status='pending_approval' where id=?", taskId);
            return;
        }
        if ("rejected".equals(task.get("approvalStatus"))) {
            jdbc.update("update team_task set status='approval_rejected' where id=?", taskId);
            return;
        }
        if (((Number) task.get("unassignedCount")).intValue() > 0) {
            jdbc.update("update team_task set status='unassigned' where id=?", taskId);
            return;
        }
        List<String> statuses = jdbc.queryForList("select status from team_task_assignee where task_id=? and is_active=true for update", String.class, taskId);
        if (statuses.isEmpty()) {
            boolean hasUnreplacedRejectedAssignee = count("select count(*) from team_task_assignee a where a.task_id=? and a.is_active=false and a.status='rejected' and not exists (select 1 from team_task_assignee replacement where replacement.task_id=a.task_id and ((replacement.reassigned_from_user_id=a.user_id and replacement.assign_round>a.assign_round) or (replacement.user_id=a.user_id and replacement.is_active=true)))", taskId) > 0;
            jdbc.update("update team_task set status=? where id=?", hasUnreplacedRejectedAssignee ? "all_rejected" : "unassigned", taskId);
            return;
        }
        boolean hasOpen = statuses.stream().anyMatch(s -> List.of("pending", "accepted").contains(s));
        boolean allRejected = statuses.stream().allMatch("rejected"::equals);
        jdbc.update("update team_task set status=? where id=?", hasOpen ? "active" : allRejected ? "all_rejected" : "completed", taskId);
    }

    private Map<String, Object> queryTaskPage(String from, List<Object> params, String order, long userId, int page, int size) {
        int safePage = Math.max(1, page);
        int safeSize = Math.min(100, Math.max(1, size));
        Integer totalValue = jdbc.queryForObject("select count(*)" + from, Integer.class, params.toArray());
        int total = totalValue == null ? 0 : totalValue;
        List<Object> pageParams = new ArrayList<>(params);
        pageParams.add(safeSize);
        pageParams.add((safePage - 1) * safeSize);
        List<Long> ids = jdbc.queryForList("select t.id" + from + " order by " + order + " limit ? offset ?", Long.class, pageParams.toArray());
        List<Map<String, Object>> rows = ids.stream().map(id -> teamTaskSummary(id, userId)).toList();
        return Map.of("list", rows, "total", total, "page", safePage, "size", safeSize);
    }

    private static void appendDateOverlap(StringBuilder sql, List<Object> params, String dateFrom, String dateTo, ZoneId zone) {
        if (!blank(dateFrom)) {
            sql.append(" and coalesce(t.deadline_time,t.start_time,t.created_at) >= ?");
            params.add(Timestamp.from(parseFilterDate(dateFrom, "dateFrom").atStartOfDay(zone).toInstant()));
        }
        if (!blank(dateTo)) {
            sql.append(" and coalesce(t.start_time,t.deadline_time,t.created_at) < ?");
            params.add(Timestamp.from(parseFilterDate(dateTo, "dateTo").plusDays(1).atStartOfDay(zone).toInstant()));
        }
    }

    private ZoneId userZone(long userId) {
        String timezone = jdbc.queryForObject("select timezone from `user` where id=? and deleted_at is null", String.class, userId);
        try {
            return ZoneId.of(timezone == null ? "Asia/Shanghai" : timezone);
        } catch (DateTimeException ex) {
            return ZoneId.of("Asia/Shanghai");
        }
    }

    private static LocalDate parseFilterDate(String value, String field) {
        try {
            return LocalDate.parse(value);
        } catch (DateTimeException ex) {
            throw new BusinessException(400, field + " is invalid");
        }
    }

    private void pausePendingReminders(long taskId) {
        jdbc.update("update reminder set status='paused' where target_type='team_task' and target_id=? and status='pending'", taskId);
    }

    private void resumePausedReminders(long taskId) {
        jdbc.update("update reminder set status='cancelled' where target_type='team_task' and target_id=? and status='paused' and remind_at<=utc_timestamp()", taskId);
        jdbc.update("update reminder set status='pending' where target_type='team_task' and target_id=? and status='paused' and remind_at>utc_timestamp()", taskId);
    }

    private void cancelActiveReminders(long taskId, Long userId) {
        if (userId == null) {
            jdbc.update("update reminder set status='cancelled' where target_type='team_task' and target_id=? and status in ('pending','paused')", taskId);
        } else {
            jdbc.update("update reminder set status='cancelled' where target_type='team_task' and target_id=? and user_id=? and status in ('pending','paused')", taskId, userId);
        }
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

    private void notifyTeamManagers(long teamId, long creatorId, long taskId, String title) {
        List<Long> managerIds = jdbc.queryForList("select user_id from team_member where team_id=? and status='active' and role in ('owner','admin')", Long.class, teamId);
        String creatorName = String.valueOf(requireUserEntity(creatorId).get("nickname"));
        for (Long managerId : managerIds) {
            if (managerId == creatorId) continue;
            notificationService.createNotification(managerId, "task_approval_requested", "团队任务待审批",
                    creatorName + " 提交了任务安排：" + title, "team_task", taskId, null);
        }
    }

    private void notifyTeamManagersOfVacancy(long teamId, long operatorId, long taskId, String title) {
        List<Long> managerIds = jdbc.queryForList("select user_id from team_member where team_id=? and status='active' and role in ('owner','admin')", Long.class, teamId);
        for (Long managerId : managerIds) {
            if (managerId == operatorId) continue;
            notificationService.createNotification(managerId, "task_unassigned", "待审批团队任务需要补位",
                    "有执行人已离开团队，审批前需重新分配：" + title, "team_task", taskId, null);
        }
    }

    private void notifyApprovalResult(Map<String, Object> task, long reviewerId, boolean approved) {
        long creatorId = longValue(task.get("creatorId"));
        if (creatorId == reviewerId) return;
        notificationService.createNotification(creatorId, approved ? "task_approved" : "task_approval_rejected",
                approved ? "团队任务已批准" : "团队任务未通过",
                (approved ? "已批准：" : "未通过：") + task.get("title"), "team_task", longValue(task.get("id")), null);
    }

    private void notifyApprovalWithdrawal(Map<String, Object> task, long operatorId) {
        long teamId = longValue(task.get("teamId"));
        List<Long> managerIds = jdbc.queryForList("select user_id from team_member where team_id=? and status='active' and role in ('owner','admin')", Long.class, teamId);
        for (Long managerId : managerIds) {
            if (managerId == operatorId) continue;
            notificationService.createNotification(managerId, "task_approval_withdrawn", "团队任务审批已撤回",
                    "审批申请已撤回：" + task.get("title"), "team_task", longValue(task.get("id")), null);
        }
    }

    private void activateAssignment(long taskId, long assigneeUserId, String title, boolean includePastReminders) {
        notificationService.createNotification(assigneeUserId, "task_assigned", "收到新的团队任务",
                "你被安排了任务：" + title, "team_task", taskId, null);
        instantiateReminderPlan(taskId, assigneeUserId, includePastReminders);
    }

    private void requirePendingApproval(Map<String, Object> task) {
        if (!"pending".equals(task.get("approvalStatus")) || !"pending_approval".equals(task.get("status"))) {
            throw new BusinessException(400, "task is not awaiting approval");
        }
    }

    private List<Long> openAssigneeUserIds(long taskId) {
        return jdbc.queryForList("select user_id from team_task_assignee where task_id=? and is_active=true and status in ('pending','accepted')", Long.class, taskId);
    }

    private void syncRemindersAfterAssigneeCorrection(long taskId, long assigneeId, String status) {
        Long assigneeUserId = jdbc.queryForObject("select user_id from team_task_assignee where id=? and task_id=?", Long.class, assigneeId, taskId);
        if (assigneeUserId == null) return;
        if (List.of("rejected", "completed").contains(status)) {
            cancelActiveReminders(taskId, assigneeUserId);
        } else {
            Map<String, Object> task = requireTeamTask(taskId);
            if (List.of("active", "unassigned").contains(String.valueOf(task.get("status")))) {
                instantiateReminderPlan(taskId, assigneeUserId);
                ensureLegacyDeadlineReminder(taskId, assigneeUserId, task);
            }
        }
        if ("completed".equals(requireTeamTask(taskId).get("status"))) cancelActiveReminders(taskId, null);
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

    private void saveReminderPlan(long taskId, Map<String, Object> req) {
        for (Timestamp remindAt : reminderTimes(req)) {
            jdbc.update("insert into team_task_reminder_plan (task_id,remind_at) select ?,? where not exists (select 1 from team_task_reminder_plan where task_id=? and remind_at=?)",
                    taskId, remindAt, taskId, remindAt);
        }
    }

    private void replaceReminderPlan(long taskId, Map<String, Object> req) {
        jdbc.update("delete from team_task_reminder_plan where task_id=?", taskId);
        saveReminderPlan(taskId, req);
    }

    private List<Timestamp> reminderTimes(Map<String, Object> req) {
        List<Timestamp> times = new ArrayList<>();
        Object arr = req.get("remindAts");
        if (arr instanceof List<?> list) {
            for (Object v : list) times.add(parseRequiredTime(v, "remindAt"));
        } else if (req.get("remindAt") != null && !String.valueOf(req.get("remindAt")).isBlank()) {
            times.add(parseRequiredTime(req.get("remindAt"), "remindAt"));
        }
        if (new HashSet<>(times).size() != times.size()) throw new BusinessException(400, "reminder times must be unique");
        return times;
    }

    private void instantiateReminderPlan(long taskId, long userId) {
        instantiateReminderPlan(taskId, userId, false);
    }

    private void instantiateReminderPlan(long taskId, long userId, boolean includePast) {
        String sql = "select remind_at from team_task_reminder_plan where task_id=?" + (includePast ? "" : " and remind_at>utc_timestamp()") + " order by remind_at";
        List<Timestamp> times = jdbc.queryForList(sql, Timestamp.class, taskId);
        for (Timestamp remindAt : times) insertReminder(userId, taskId, remindAt, includePast);
    }

    private void ensureLegacyDeadlineReminder(long taskId, long userId, Map<String, Object> task) {
        if (count("select count(*) from team_task_reminder_plan where task_id=?", taskId) > 0) return;
        Timestamp deadline = parseTime(String.valueOf(task.getOrDefault("deadlineTime", "")));
        if (deadline != null && deadline.after(Timestamp.from(Instant.now()))) insertReminder(userId, taskId, deadline);
    }

    private void insertReminder(long userId, long taskId, Timestamp remindAt) {
        insertReminder(userId, taskId, remindAt, false);
    }

    private void insertReminder(long userId, long taskId, Timestamp remindAt, boolean includePast) {
        if (remindAt != null && (includePast || remindAt.after(Timestamp.from(Instant.now())))) {
            jdbc.update("insert into reminder (user_id,target_type,target_id,remind_at,status) select ?,'team_task',?,?,'pending' where not exists (select 1 from reminder where user_id=? and target_type='team_task' and target_id=? and remind_at=? and status in ('pending','paused'))",
                    userId, taskId, remindAt, userId, taskId, remindAt);
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
        return parseRequiredTime(value, field);
    }

    private static Timestamp parseRequiredTime(Object value, String field) { Timestamp parsed = parseTime(String.valueOf(value)); if (parsed == null) throw new BusinessException(400, field + " is invalid"); return parsed; }

    private static void validateTimeRange(Timestamp startTime, Timestamp deadlineTime) { if (startTime != null && deadlineTime != null && deadlineTime.before(startTime)) throw new BusinessException(400, "deadlineTime must not be before startTime"); }

    private static Timestamp parseTime(String value) {
        if (value == null || value.isBlank()) return null;
        try { return Timestamp.from(OffsetDateTime.parse(value).toInstant()); } catch (Exception ignored) {}
        try { return Timestamp.valueOf(LocalDateTime.parse(value)); } catch (Exception ignored) {}
        return null;
    }
}
