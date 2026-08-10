package com.dayliane.reminder;

import com.dayliane.notification.NotificationService;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Timestamp;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Service
public class ReminderService {

    private final JdbcTemplate jdbc;
    private final NotificationService notificationService;

    public ReminderService(JdbcTemplate jdbc, NotificationService notificationService) {
        this.jdbc = jdbc;
        this.notificationService = notificationService;
    }

    @Transactional
    public int scanReminders() {
        List<Map<String, Object>> due = jdbc.query("select id,user_id userId,target_type targetType,target_id targetId from reminder where status='pending' and remind_at <= utc_timestamp()", (rs, i) -> {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", rs.getLong("id"));
            m.put("userId", rs.getLong("userId"));
            m.put("targetType", rs.getString("targetType"));
            m.put("targetId", rs.getLong("targetId"));
            return m;
        });
        int sentCount = 0;
        for (Map<String, Object> item : due) {
            int claimed = jdbc.update("update reminder set status='processing' where id=? and status='pending'", item.get("id"));
            if (claimed == 0) continue;
            if (!targetIsActive(item)) {
                jdbc.update("update reminder set status='cancelled' where id=? and status='processing'", item.get("id"));
                continue;
            }
            boolean sent = notificationService.createNotification(
                    ((Number) item.get("userId")).longValue(), "reminder", "日程提醒", "你有一项日程或任务即将到期",
                    String.valueOf(item.get("targetType")), ((Number) item.get("targetId")).longValue(), ((Number) item.get("id")).longValue());
            jdbc.update("update reminder set status=?, sent_at=case when ? then utc_timestamp() else sent_at end where id=? and status='processing'",
                    sent ? "sent" : "cancelled", sent, item.get("id"));
            if (sent) sentCount++;
        }
        return sentCount;
    }

    public Map<String, Object> listMyReminders(long userId, int page, int size, String status, String targetType) {
        StringBuilder sql = new StringBuilder("select r.id,r.user_id userId,r.target_type targetType,r.target_id targetId,r.remind_at remindAt,r.status,r.sent_at sentAt,r.error_message errorMessage,r.created_at createdAt,coalesce(s.title,t.title,'已删除记录') targetTitle from reminder r left join schedule s on r.target_type='schedule' and s.id=r.target_id left join team_task t on r.target_type='team_task' and t.id=r.target_id where r.user_id=?");
        List<Object> params = new ArrayList<>();
        params.add(userId);
        if (status != null && !status.isBlank()) { sql.append(" and r.status=?"); params.add(status); }
        if (targetType != null && !targetType.isBlank()) { sql.append(" and r.target_type=?"); params.add(targetType); }
        Integer totalValue = jdbc.queryForObject("select count(*) from (" + sql + ") filtered", Integer.class, params.toArray());
        int total = totalValue == null ? 0 : totalValue;
        int safePage = Math.max(1, page);
        int safeSize = Math.min(100, Math.max(1, size));
        sql.append(" order by r.remind_at desc,r.id desc limit ? offset ?");
        params.add(safeSize);
        params.add((safePage - 1) * safeSize);
        List<Map<String, Object>> rows = jdbc.query(sql.toString(), (rs, i) -> {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("id", rs.getLong("id"));
            row.put("userId", rs.getLong("userId"));
            row.put("targetType", rs.getString("targetType"));
            row.put("targetId", rs.getLong("targetId"));
            row.put("targetTitle", rs.getString("targetTitle"));
            row.put("remindAt", iso(rs.getTimestamp("remindAt")));
            row.put("status", rs.getString("status"));
            row.put("sentAt", iso(rs.getTimestamp("sentAt")));
            row.put("errorMessage", rs.getString("errorMessage"));
            row.put("createdAt", iso(rs.getTimestamp("createdAt")));
            return row;
        }, params.toArray());
        return Map.of("list", rows, "total", total, "page", safePage, "size", safeSize);
    }

    private boolean targetIsActive(Map<String, Object> reminder) {
        String type = String.valueOf(reminder.get("targetType"));
        long targetId = ((Number) reminder.get("targetId")).longValue();
        long userId = ((Number) reminder.get("userId")).longValue();
        Integer count;
        if ("schedule".equals(type)) {
            count = jdbc.queryForObject("select count(*) from schedule where id=? and user_id=? and status='pending' and deleted_at is null", Integer.class, targetId, userId);
        } else if ("team_task".equals(type)) {
            count = jdbc.queryForObject("select count(*) from team_task t join team_task_assignee a on a.task_id=t.id and a.user_id=? and a.is_active=true and a.status in ('pending','accepted') where t.id=? and t.approval_status='approved' and t.status in ('active','unassigned') and t.deleted_at is null", Integer.class, userId, targetId);
        } else {
            return false;
        }
        return count != null && count > 0;
    }

    private static String iso(Timestamp timestamp) {
        return timestamp == null ? "" : OffsetDateTime.ofInstant(timestamp.toInstant(), ZoneOffset.UTC).toString();
    }
}
