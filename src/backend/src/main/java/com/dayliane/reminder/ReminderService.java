package com.dayliane.reminder;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Service
public class ReminderService {

    private final JdbcTemplate jdbc;

    public ReminderService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
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
        for (Map<String, Object> item : due) {
            jdbc.update("insert into notification (user_id,type,title,content,related_type,related_id,reminder_id,is_read) values (?,?,?,?,?,?,?,false)",
                    item.get("userId"), "reminder", "Reminder", "A schedule or task is due soon", item.get("targetType"), item.get("targetId"), item.get("id"));
            jdbc.update("update reminder set status='sent', sent_at=utc_timestamp() where id=?", item.get("id"));
        }
        return due.size();
    }
}
