package com.dayliane.admin;

import com.dayliane.common.BusinessException;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.sql.PreparedStatement;
import java.sql.Statement;
import java.sql.Timestamp;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.*;

@Service
public class AdminService {
    private final JdbcTemplate jdbc;
    private final BCryptPasswordEncoder passwordEncoder = new BCryptPasswordEncoder();

    public AdminService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    // ==================== Admin user management ====================

    public Map<String, Object> adminView(long adminId) {
        Map<String, Object> admin = requireAdminEntity(adminId);
        admin.remove("passwordHash");
        return admin;
    }

    private Map<String, Object> requireAdminEntity(long adminId) {
        try {
            return jdbc.queryForObject("select id,username,password_hash passwordHash,role,status,last_login_at lastLoginAt,last_login_ip lastLoginIp,created_at createdAt from admin_user where id=?", adminMapper(), adminId);
        } catch (EmptyResultDataAccessException ex) {
            throw new BusinessException(404, "admin user not found");
        }
    }

    private Map<String, Object> findAdminByUsername(String username) {
        try {
            return jdbc.queryForObject("select id,username,password_hash passwordHash,role,status,last_login_at lastLoginAt,last_login_ip lastLoginIp,created_at createdAt from admin_user where username=?", adminMapper(), username);
        } catch (EmptyResultDataAccessException ex) {
            return null;
        }
    }

    // ==================== Admin CRUD ====================

    public Map<String, Object> adminList(String table, int page, int size) {
        return adminList(table, page, size, null, null, null, null);
    }

    public Map<String, Object> adminList(String table, int page, int size, String keyword, String status, String dateFrom, String dateTo) {
        StringBuilder sql = new StringBuilder();
        List<Object> params = new ArrayList<>();

        switch (table) {
            case "users" -> {
                sql.append("select id,phone,nickname,timezone,status,created_at createdAt from `user` where deleted_at is null");
                if (!blank(keyword)) { sql.append(" and (phone like ? or nickname like ?)"); String kw = "%" + keyword + "%"; params.add(kw); params.add(kw); }
                if (!blank(status)) { sql.append(" and status=?"); params.add(status); }
            }
            case "teams" -> {
                sql.append("select id,name,invite_code inviteCode,owner_id ownerId,status,created_at createdAt from team where deleted_at is null");
                if (!blank(keyword)) { sql.append(" and name like ?"); params.add("%" + keyword + "%"); }
                if (!blank(status)) { sql.append(" and status=?"); params.add(status); }
            }
            case "schedules" -> {
                sql.append("select id,user_id userId,title,group_name groupName,time_type timeType,status,created_at createdAt from schedule where deleted_at is null");
                if (!blank(keyword)) { sql.append(" and title like ?"); params.add("%" + keyword + "%"); }
                if (!blank(status)) { sql.append(" and status=?"); params.add(status); }
            }
            case "teamTasks" -> {
                sql.append("select id,team_id teamId,creator_id creatorId,title,status,deadline_time deadlineTime,created_at createdAt from team_task where deleted_at is null");
                if (!blank(keyword)) { sql.append(" and title like ?"); params.add("%" + keyword + "%"); }
                if (!blank(status)) { sql.append(" and status=?"); params.add(status); }
            }
            case "notifications" -> {
                sql.append("select id,user_id userId,type,title,related_type relatedType,related_id relatedId,is_read isRead,created_at createdAt from notification where deleted_at is null");
                if (!blank(keyword)) { sql.append(" and title like ?"); params.add("%" + keyword + "%"); }
                if (!blank(status)) { sql.append(" and type=?"); params.add(status); }
            }
            case "reminders" -> {
                sql.append("select id,user_id userId,target_type targetType,target_id targetId,remind_at remindAt,status,created_at createdAt from reminder where 1=1");
                if (!blank(status)) { sql.append(" and status=?"); params.add(status); }
            }
            case "adminUsers" -> {
                sql.append("select id,username,role,status,last_login_at lastLoginAt,last_login_ip lastLoginIp,created_at createdAt from admin_user where 1=1");
                if (!blank(keyword)) { sql.append(" and (username like ? or role like ?)"); String kw = "%" + keyword + "%"; params.add(kw); params.add(kw); }
                if (!blank(status)) { sql.append(" and status=?"); params.add(status); }
            }
            default -> throw new BusinessException(404, "admin resource not found");
        }

        if (!blank(dateFrom)) { sql.append(" and created_at >= ?"); params.add(dateFrom + " 00:00:00"); }
        if (!blank(dateTo)) { sql.append(" and created_at <= ?"); params.add(dateTo + " 23:59:59"); }
        sql.append(" order by created_at desc");

        List<Map<String, Object>> rows = jdbc.query(sql.toString(), (rs, i) -> {
            Map<String, Object> m = new LinkedHashMap<>();
            int colCount = rs.getMetaData().getColumnCount();
            for (int c = 1; c <= colCount; c++) {
                m.put(rs.getMetaData().getColumnLabel(c), rs.getObject(c));
            }
            return m;
        }, params.toArray()).stream().map(this::normalizeAdminRow).toList();
        return pageResult(rows, page, size);
    }

    // ==================== Details ====================

    public Map<String, Object> adminUserDetail(long id) {
        try {
            Map<String, Object> user = jdbc.queryForObject(
                    "select id,phone,nickname,avatar_url avatarUrl,timezone,status,created_at createdAt from `user` where id=? and deleted_at is null",
                    (rs, i) -> {
                        Map<String, Object> m = new LinkedHashMap<>();
                        m.put("id", rs.getLong("id"));
                        m.put("phone", rs.getString("phone"));
                        m.put("nickname", rs.getString("nickname"));
                        m.put("avatarUrl", rs.getString("avatarUrl"));
                        m.put("timezone", rs.getString("timezone"));
                        m.put("status", rs.getString("status"));
                        m.put("createdAt", iso(rs.getTimestamp("createdAt")));
                        return m;
                    }, id);
            return normalizeAdminRow(user);
        } catch (EmptyResultDataAccessException ex) {
            throw new BusinessException(404, "user not found");
        }
    }

    public Map<String, Object> adminTeamDetail(long id) {
        Map<String, Object> team = requireTeamOnly(id);
        team.put("members", activeMembersOnly(id));
        return team;
    }

    private Map<String, Object> requireTeamOnly(long teamId) {
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

    private List<Map<String, Object>> activeMembersOnly(long teamId) {
        return jdbc.query("select m.id,m.team_id teamId,m.user_id userId,m.role,m.status,m.joined_at joinedAt,u.nickname,u.phone from team_member m join `user` u on u.id=m.user_id where m.team_id=? and m.status='active' order by m.id", (rs, i) -> {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", rs.getLong("id"));
            m.put("teamId", rs.getLong("teamId"));
            m.put("userId", rs.getLong("userId"));
            m.put("role", rs.getString("role"));
            m.put("status", rs.getString("status"));
            m.put("joinedAt", iso(rs.getTimestamp("joinedAt")));
            m.put("nickname", rs.getString("nickname"));
            m.put("phone", rs.getString("phone"));
            return m;
        }, teamId);
    }

    public Map<String, Object> adminScheduleDetail(long id) {
        try {
            Map<String, Object> schedule = jdbc.queryForObject(
                    "select id,user_id userId,title,description,group_id groupId,group_name groupName,time_type timeType,start_time startTime,end_time endTime,deadline_time deadlineTime,status,created_at createdAt from schedule where id=? and deleted_at is null",
                    scheduleMapper(), id);
            Map<String, Object> user = jdbc.queryForObject(
                    "select id,phone,nickname,timezone,status from `user` where id=? and deleted_at is null",
                    (rs, i) -> {
                        Map<String, Object> m = new LinkedHashMap<>();
                        m.put("id", rs.getLong("id"));
                        m.put("phone", rs.getString("phone"));
                        m.put("nickname", rs.getString("nickname"));
                        m.put("timezone", rs.getString("timezone"));
                        m.put("status", rs.getString("status"));
                        return m;
                    }, schedule.get("userId"));
            schedule.put("user", user);
            return schedule;
        } catch (EmptyResultDataAccessException ex) {
            throw new BusinessException(404, "schedule not found");
        }
    }

    public Map<String, Object> adminTeamTaskDetail(long id) {
        try {
            Map<String, Object> task = jdbc.queryForObject(
                    "select id,team_id teamId,creator_id creatorId,title,description,group_name groupName,start_time startTime,deadline_time deadlineTime,status,created_at createdAt from team_task where id=? and deleted_at is null",
                    teamTaskMapper(), id);
            task.put("assignees", teamTaskAssignees(id));
            task.put("creator", userView(longValue(task.get("creatorId"))));
            return task;
        } catch (EmptyResultDataAccessException ex) {
            throw new BusinessException(404, "team task not found");
        }
    }

    public Map<String, Object> adminNotificationDetail(long id) {
        try {
            return jdbc.queryForObject("select id,user_id userId,type,title,content,related_type relatedType,related_id relatedId,reminder_id reminderId,is_read isRead,read_at readAt,created_at createdAt from notification where id=? and deleted_at is null",
                    notificationMapper(), id);
        } catch (EmptyResultDataAccessException ex) {
            throw new BusinessException(404, "notification not found");
        }
    }

    public Map<String, Object> adminReminderDetail(long id) {
        try {
            return jdbc.queryForObject("select id,user_id userId,target_type targetType,target_id targetId,remind_at remindAt,status,sent_at sentAt,created_at createdAt from reminder where id=?",
                    (rs, i) -> {
                        Map<String, Object> m = new LinkedHashMap<>();
                        m.put("id", rs.getLong("id"));
                        m.put("userId", rs.getLong("userId"));
                        m.put("targetType", rs.getString("targetType"));
                        m.put("targetId", rs.getObject("targetId"));
                        m.put("remindAt", iso(rs.getTimestamp("remindAt")));
                        m.put("status", rs.getString("status"));
                        m.put("sentAt", iso(rs.getTimestamp("sentAt")));
                        m.put("createdAt", iso(rs.getTimestamp("createdAt")));
                        return m;
                    }, id);
        } catch (EmptyResultDataAccessException ex) {
            throw new BusinessException(404, "reminder not found");
        }
    }

    // ==================== Admin operations ====================

    @Transactional
    public Map<String, Object> adminSetUserStatus(long adminId, long userId, String status, String ipAddress, String userAgent) {
        if (!List.of("active", "disabled").contains(status)) throw new BusinessException(400, "status is invalid");
        Map<String, Object> before = userView(userId);
        int updated = jdbc.update("update `user` set status=? where id=? and deleted_at is null", status, userId);
        if (updated == 0) throw new BusinessException(404, "user not found");
        Map<String, Object> after = userView(userId);
        writeAdminOperationLog(adminId, "set_user_status", "user", userId, before, after, ipAddress, userAgent);
        return after;
    }

    @Transactional
    public Map<String, Object> createAdminUser(long adminId, Map<String, Object> req, String ipAddress, String userAgent) {
        String username = String.valueOf(req.getOrDefault("username", "")).trim();
        String password = String.valueOf(req.getOrDefault("password", ""));
        String role = String.valueOf(req.getOrDefault("role", "admin")).trim();
        if (username.isBlank()) throw new BusinessException(400, "username is required");
        if (password.length() < 8 || !password.matches(".*[A-Za-z].*") || !password.matches(".*\\d.*")) throw new BusinessException(400, "password format is invalid");
        if (role.isBlank()) throw new BusinessException(400, "role is required");
        if (count("select count(*) from admin_user where username=?", username) > 0) throw new BusinessException(409, "admin username already exists");
        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbc.update(con -> {
            PreparedStatement ps = con.prepareStatement("insert into admin_user (username,password_hash,role,status) values (?,?,?, 'active')", new String[]{"id"});
            ps.setString(1, username);
            ps.setString(2, passwordEncoder.encode(password));
            ps.setString(3, role);
            return ps;
        }, keyHolder);
        long createdId = Objects.requireNonNull(keyHolder.getKey()).longValue();
        Map<String, Object> after = adminView(createdId);
        writeAdminOperationLog(adminId, "create_admin_user", "admin_user", createdId, null, after, ipAddress, userAgent);
        return after;
    }

    @Transactional
    public Map<String, Object> adminSetAdminUserStatus(long adminId, long targetAdminId, String status, String ipAddress, String userAgent) {
        if (!List.of("active", "disabled").contains(status)) throw new BusinessException(400, "status is invalid");
        if (adminId == targetAdminId && "disabled".equals(status)) throw new BusinessException(400, "cannot disable yourself");
        Map<String, Object> before = adminView(targetAdminId);
        int updated = jdbc.update("update admin_user set status=? where id=?", status, targetAdminId);
        if (updated == 0) throw new BusinessException(404, "admin user not found");
        Map<String, Object> after = adminView(targetAdminId);
        writeAdminOperationLog(adminId, "set_admin_user_status", "admin_user", targetAdminId, before, after, ipAddress, userAgent);
        return after;
    }

    // ==================== Operation logs ====================

    public Map<String, Object> adminOperationLogs(int page, int size, String adminId, String action, String targetType, String dateFrom, String dateTo) {
        StringBuilder sql = new StringBuilder("select id,admin_id adminId,action,target_type targetType,target_id targetId,before_data beforeData,after_data afterData,ip_address ipAddress,user_agent userAgent,created_at createdAt from admin_operation_log where 1=1");
        List<Object> params = new ArrayList<>();
        if (!blank(adminId)) { sql.append(" and admin_id=?"); try { params.add(Long.parseLong(adminId)); } catch (NumberFormatException ignored) {} }
        if (!blank(action)) { sql.append(" and action like ?"); params.add("%" + action + "%"); }
        if (!blank(targetType)) { sql.append(" and target_type=?"); params.add(targetType); }
        if (!blank(dateFrom)) { sql.append(" and created_at >= ?"); params.add(dateFrom + " 00:00:00"); }
        if (!blank(dateTo)) { sql.append(" and created_at <= ?"); params.add(dateTo + " 23:59:59"); }
        sql.append(" order by created_at desc");

        List<Map<String, Object>> rows = jdbc.query(sql.toString(), (rs, i) -> {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", rs.getLong("id"));
            m.put("adminId", rs.getLong("adminId"));
            m.put("action", rs.getString("action"));
            m.put("targetType", rs.getString("targetType"));
            m.put("targetId", rs.getObject("targetId"));
            m.put("beforeData", rs.getString("beforeData"));
            m.put("afterData", rs.getString("afterData"));
            m.put("ipAddress", rs.getString("ipAddress"));
            m.put("userAgent", rs.getString("userAgent"));
            m.put("createdAt", iso(rs.getTimestamp("createdAt")));
            return m;
        }, params.toArray());

        return pageResult(rows, page, size);
    }

    public void writeAdminOperationLog(long adminId, String action, String targetType, Long targetId, Map<String, Object> beforeData, Map<String, Object> afterData, String ipAddress, String userAgent) {
        jdbc.update("insert into admin_operation_log (admin_id,action,target_type,target_id,before_data,after_data,ip_address,user_agent) values (?,?,?,?,cast(? as json),cast(? as json),?,?)",
                adminId, action, targetType, targetId, toJson(beforeData), toJson(afterData), ipAddress, userAgent);
    }

    // ==================== Internal helpers ====================

    private Map<String, Object> normalizeAdminRow(Map<String, Object> raw) {
        Map<String, Object> out = new LinkedHashMap<>();
        raw.forEach((key, value) -> out.put(key, value instanceof Timestamp ts ? iso(ts) : value));
        return out;
    }

    public Map<String, Object> userView(long userId) {
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

    private List<Map<String, Object>> teamTaskAssignees(long taskId) {
        return jdbc.query("select a.id,a.user_id userId,a.assign_round assignRound,a.is_active isActive,a.status,u.nickname from team_task_assignee a join `user` u on u.id=a.user_id where a.task_id=? and a.is_active=true order by a.id", (rs, i) -> {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", rs.getLong("id"));
            m.put("userId", rs.getLong("userId"));
            m.put("assignRound", rs.getInt("assignRound"));
            m.put("isActive", rs.getBoolean("isActive"));
            m.put("status", rs.getString("status"));
            m.put("assignStatus", rs.getString("status"));
            m.put("nickname", rs.getString("nickname"));
            return m;
        }, taskId);
    }

    public Map<String, Object> pageResult(List<Map<String, Object>> rows, int page, int size) {
        int p = Math.max(1, page);
        int s = Math.min(100, Math.max(1, size));
        int from = Math.min(rows.size(), (p - 1) * s);
        int to = Math.min(rows.size(), from + s);
        return Map.of("list", rows.subList(from, to), "total", rows.size(), "page", p, "size", s);
    }

    private RowMapper<Map<String, Object>> adminMapper() {
        return (rs, i) -> {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", rs.getLong("id"));
            m.put("username", rs.getString("username"));
            m.put("passwordHash", rs.getString("passwordHash"));
            m.put("role", rs.getString("role"));
            m.put("status", rs.getString("status"));
            m.put("lastLoginAt", iso(rs.getTimestamp("lastLoginAt")));
            m.put("lastLoginIp", rs.getString("lastLoginIp"));
            m.put("createdAt", iso(rs.getTimestamp("createdAt")));
            return m;
        };
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

    private RowMapper<Map<String, Object>> teamTaskMapper() {
        return (rs, i) -> {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", rs.getLong("id"));
            m.put("teamId", rs.getLong("teamId"));
            m.put("creatorId", rs.getLong("creatorId"));
            m.put("title", rs.getString("title"));
            m.put("description", rs.getString("description"));
            m.put("groupName", rs.getString("groupName"));
            m.put("startTime", iso(rs.getTimestamp("startTime")));
            m.put("deadlineTime", iso(rs.getTimestamp("deadlineTime")));
            m.put("status", rs.getString("status"));
            m.put("createdAt", iso(rs.getTimestamp("createdAt")));
            return m;
        };
    }

    private RowMapper<Map<String, Object>> scheduleMapper() {
        return (rs, i) -> {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", rs.getLong("id"));
            m.put("userId", rs.getLong("userId"));
            m.put("title", rs.getString("title"));
            m.put("description", rs.getString("description"));
            m.put("groupId", rs.getObject("groupId"));
            m.put("groupName", rs.getString("groupName"));
            m.put("timeType", rs.getString("timeType"));
            m.put("startTime", iso(rs.getTimestamp("startTime")));
            m.put("endTime", iso(rs.getTimestamp("endTime")));
            m.put("deadlineTime", iso(rs.getTimestamp("deadlineTime")));
            m.put("status", rs.getString("status"));
            m.put("createdAt", iso(rs.getTimestamp("createdAt")));
            return m;
        };
    }

    private RowMapper<Map<String, Object>> notificationMapper() {
        return (rs, i) -> {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", rs.getLong("id"));
            m.put("userId", rs.getLong("userId"));
            m.put("type", rs.getString("type"));
            m.put("title", rs.getString("title"));
            m.put("content", rs.getString("content"));
            m.put("relatedType", rs.getString("relatedType"));
            m.put("relatedId", rs.getObject("relatedId"));
            m.put("reminderId", rs.getObject("reminderId"));
            m.put("isRead", rs.getBoolean("isRead"));
            m.put("readAt", iso(rs.getTimestamp("readAt")));
            m.put("createdAt", iso(rs.getTimestamp("createdAt")));
            return m;
        };
    }

    private static String toJson(Map<String, Object> data) {
        if (data == null) return null;
        StringBuilder json = new StringBuilder("{");
        int i = 0;
        for (Map.Entry<String, Object> entry : data.entrySet()) {
            if (i++ > 0) json.append(',');
            json.append('"').append(jsonEscape(entry.getKey())).append("\":");
            Object value = entry.getValue();
            if (value == null) json.append("null");
            else if (value instanceof Number || value instanceof Boolean) json.append(value);
            else json.append('"').append(jsonEscape(String.valueOf(value))).append('"');
        }
        return json.append('}').toString();
    }

    private static String jsonEscape(String value) {
        return value.replace("\\", "\\\\").replace("\"", "\\\"").replace("\r", "\\r").replace("\n", "\\n");
    }

    private static String iso(Timestamp ts) {
        return ts == null ? "" : OffsetDateTime.ofInstant(ts.toInstant(), ZoneOffset.UTC).toString();
    }

    private static boolean blank(String s) {
        return s == null || s.isBlank();
    }

    private static long longValue(Object v) {
        return ((Number) v).longValue();
    }

    private Integer count(String sql, Object... args) {
        Integer n = jdbc.queryForObject(sql, Integer.class, args);
        return n == null ? 0 : n;
    }
}
