package com.dayliane.admin;

import com.dayliane.auth.email.EmailAddress;
import com.dayliane.common.BusinessException;
import com.dayliane.common.RefreshTokenStore;
import com.dayliane.fatigue.FatigueService;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.dao.DuplicateKeyException;
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
import java.nio.charset.StandardCharsets;
import java.time.DateTimeException;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.*;

@Service
public class AdminService {
    private final JdbcTemplate jdbc;
    private final FatigueService fatigueService;
    private final ObjectMapper objectMapper;
    private final RefreshTokenStore refreshTokenStore;
    private final BCryptPasswordEncoder passwordEncoder = new BCryptPasswordEncoder();

    public AdminService(JdbcTemplate jdbc, FatigueService fatigueService, ObjectMapper objectMapper,
                        RefreshTokenStore refreshTokenStore) {
        this.jdbc = jdbc;
        this.fatigueService = fatigueService;
        this.objectMapper = objectMapper;
        this.refreshTokenStore = refreshTokenStore;
    }

    // ==================== Admin user management ====================

    public Map<String, Object> adminView(long adminId) {
        Map<String, Object> admin = requireAdminEntity(adminId);
        admin.remove("passwordHash");
        return admin;
    }

    public void requireSuperAdmin(long adminId) {
        Map<String, Object> admin = requireAdminEntity(adminId);
        if (!"active".equals(admin.get("status")) || !"super_admin".equals(admin.get("role"))) {
            throw new BusinessException(403, "super admin permission is required");
        }
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
        return adminList(table, page, size, null, null, null, null, null);
    }

    public Map<String, Object> adminList(String table, int page, int size, String keyword, String status, String dateFrom, String dateTo) {
        return adminList(table, page, size, keyword, status, dateFrom, dateTo, null);
    }

    public Map<String, Object> adminList(String table, int page, int size, String keyword, String status, String dateFrom, String dateTo, String sort) {
        StringBuilder sql = new StringBuilder();
        List<Object> params = new ArrayList<>();

        switch (table) {
            case "users" -> {
                sql.append("select id,phone,email,email_verified_at emailVerifiedAt,nickname,timezone,status,profile_version profileVersion,created_at createdAt from `user` where deleted_at is null");
                if (!blank(keyword)) { sql.append(" and (phone like ? or email like ? or nickname like ?)"); String kw = "%" + keyword + "%"; params.add(kw); params.add(kw); params.add(kw); }
                if (!blank(status)) { sql.append(" and status=?"); params.add(status); }
            }
            case "teams" -> {
                sql.append("select id,name,invite_code inviteCode,owner_id ownerId,status,created_at createdAt from team where deleted_at is null");
                if (!blank(keyword)) { sql.append(" and name like ?"); params.add("%" + keyword + "%"); }
                if (!blank(status)) { sql.append(" and status=?"); params.add(status); }
            }
            case "schedules" -> {
                sql.append("select id,user_id userId,title,group_name groupName,time_type timeType,status,urgency_level urgencyLevel,fatigue_level fatigueLevel,completed_at completedAt,completed_fatigue_level completedFatigueLevel,completed_fatigue_weight completedFatigueWeight,created_at createdAt from schedule where deleted_at is null");
                if (!blank(keyword)) { sql.append(" and title like ?"); params.add("%" + keyword + "%"); }
                if (!blank(status)) { sql.append(" and status=?"); params.add(status); }
            }
            case "teamTasks" -> {
                sql.append("select id,team_id teamId,creator_id creatorId,title,status,deadline_time deadlineTime,created_at createdAt from team_task where deleted_at is null");
                if (!blank(keyword)) { sql.append(" and title like ?"); params.add("%" + keyword + "%"); }
                if (!blank(status)) { sql.append(" and status=?"); params.add(status); }
            }
            case "notifications" -> {
                sql.append("select id,user_id userId,type,title,related_type relatedType,related_id relatedId,target_route targetRoute,data_revision dataRevision,is_read isRead,created_at createdAt from notification where deleted_at is null");
                if (!blank(keyword)) { sql.append(" and title like ?"); params.add("%" + keyword + "%"); }
                if ("read".equals(status)) sql.append(" and is_read=true");
                else if ("unread".equals(status)) sql.append(" and is_read=false");
                else if (!blank(status)) throw new BusinessException(400, "notification status is invalid");
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
        Integer totalValue = jdbc.queryForObject("select count(*) from (" + sql + ") filtered", Integer.class, params.toArray());
        int total = totalValue == null ? 0 : totalValue;
        int safePage = Math.max(1, page);
        int safeSize = Math.min(100, Math.max(1, size));
        sql.append(" order by ").append(adminSort(table, sort));
        sql.append(" limit ? offset ?");
        params.add(safeSize);
        params.add((safePage - 1) * safeSize);

        List<Map<String, Object>> rows = jdbc.query(sql.toString(), (rs, i) -> {
            Map<String, Object> m = new LinkedHashMap<>();
            int colCount = rs.getMetaData().getColumnCount();
            for (int c = 1; c <= colCount; c++) {
                m.put(rs.getMetaData().getColumnLabel(c), rs.getObject(c));
            }
            return m;
        }, params.toArray()).stream().map(this::normalizeAdminRow).toList();
        return pagedResult(rows, total, safePage, safeSize);
    }

    public Map<String, Object> dashboardStats() {
        Map<String, Object> stats = new LinkedHashMap<>();
        stats.put("users", count("select count(*) from `user` where deleted_at is null"));
        stats.put("activeUsers", count("select count(*) from `user` where status='active' and deleted_at is null"));
        stats.put("teams", count("select count(*) from team where deleted_at is null"));
        stats.put("pendingSchedules", count("select count(*) from schedule where status='pending' and deleted_at is null"));
        stats.put("activeTeamTasks", count("select count(*) from team_task where status in ('active','unassigned') and deleted_at is null"));
        stats.put("pendingReminders", count("select count(*) from reminder where status='pending'"));
        stats.put("unreadNotifications", count("select count(*) from notification where is_read=false and deleted_at is null"));
        stats.put("aiCallsToday", count("select count(*) from ai_usage_log where created_at >= current_date"));
        return stats;
    }

    private String adminSort(String table, String sort) {
        Map<String, String> allowed = new LinkedHashMap<>();
        allowed.put("id", "id");
        allowed.put("createdAt", "created_at");
        allowed.put("status", "notifications".equals(table) ? "is_read" : "status");
        if (List.of("schedules", "teamTasks", "notifications").contains(table)) allowed.put("title", "title");
        if ("teamTasks".equals(table)) allowed.put("deadlineTime", "deadline_time");
        if ("reminders".equals(table)) allowed.put("remindAt", "remind_at");
        if ("users".equals(table)) { allowed.put("phone", "phone"); allowed.put("email", "email"); allowed.put("nickname", "nickname"); }
        if ("teams".equals(table)) allowed.put("name", "name");
        if ("adminUsers".equals(table)) { allowed.put("username", "username"); allowed.put("role", "role"); }
        if (sort == null || sort.isBlank()) return "created_at desc";
        String[] parts = sort.split(",", 2);
        String column = allowed.get(parts[0]);
        String direction = parts.length > 1 && "asc".equalsIgnoreCase(parts[1]) ? "asc" : "desc";
        if (column == null) throw new BusinessException(400, "sort field is invalid");
        return column + " " + direction + ",id " + direction;
    }

    // ==================== Details ====================

    public Map<String, Object> adminUserDetail(long id) {
        try {
            Map<String, Object> user = jdbc.queryForObject(
                    "select id,phone,email,email_verified_at emailVerifiedAt,nickname,avatar_url avatarUrl,timezone,status,profile_version profileVersion,created_at createdAt from `user` where id=? and deleted_at is null",
                    (rs, i) -> {
                        Map<String, Object> m = new LinkedHashMap<>();
                        m.put("id", rs.getLong("id"));
                        m.put("phone", rs.getString("phone"));
                        m.put("email", rs.getString("email"));
                        m.put("emailVerifiedAt", iso(rs.getTimestamp("emailVerifiedAt")));
                        m.put("nickname", rs.getString("nickname"));
                        m.put("avatarUrl", rs.getString("avatarUrl"));
                        m.put("timezone", rs.getString("timezone"));
                        m.put("status", rs.getString("status"));
                        m.put("profileVersion", rs.getLong("profileVersion"));
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
        team.put("memberCount", count("select count(*) from team_member where team_id=? and status='active'", id));
        team.put("taskCount", count("select count(*) from team_task where team_id=? and deleted_at is null", id));
        team.put("activeTaskCount", count("select count(*) from team_task where team_id=? and status in ('active','unassigned') and deleted_at is null", id));
        team.put("members", teamMembersOnly(id));
        team.put("recentTasks", jdbc.query("select id,title,status,creator_id creatorId,deadline_time deadlineTime,created_at createdAt from team_task where team_id=? and deleted_at is null order by created_at desc,id desc limit 20", (rs, i) -> {
            Map<String, Object> task = new LinkedHashMap<>();
            task.put("id", rs.getLong("id"));
            task.put("title", rs.getString("title"));
            task.put("status", rs.getString("status"));
            task.put("creatorId", rs.getLong("creatorId"));
            task.put("deadlineTime", iso(rs.getTimestamp("deadlineTime")));
            task.put("createdAt", iso(rs.getTimestamp("createdAt")));
            return task;
        }, id));
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

    private List<Map<String, Object>> teamMembersOnly(long teamId) {
        return jdbc.query("select m.id,m.team_id teamId,m.user_id userId,m.role,m.status,m.joined_at joinedAt,m.removed_at removedAt,u.nickname,u.phone from team_member m join `user` u on u.id=m.user_id where m.team_id=? order by case when m.status='active' then 0 else 1 end,m.id", (rs, i) -> {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", rs.getLong("id"));
            m.put("teamId", rs.getLong("teamId"));
            m.put("userId", rs.getLong("userId"));
            m.put("role", rs.getString("role"));
            m.put("status", rs.getString("status"));
            m.put("joinedAt", iso(rs.getTimestamp("joinedAt")));
            m.put("removedAt", iso(rs.getTimestamp("removedAt")));
            m.put("nickname", rs.getString("nickname"));
            m.put("phone", rs.getString("phone"));
            return m;
        }, teamId);
    }

    public Map<String, Object> adminScheduleDetail(long id) {
        try {
            Map<String, Object> schedule = jdbc.queryForObject(
                    "select id,user_id userId,title,description,group_id groupId,group_name groupName,time_type timeType,start_time startTime,end_time endTime,deadline_time deadlineTime,status,urgency_level urgencyLevel,fatigue_level fatigueLevel,completed_at completedAt,completed_fatigue_level completedFatigueLevel,completed_fatigue_weight completedFatigueWeight,created_at createdAt from schedule where id=? and deleted_at is null",
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
                    "select id,team_id teamId,creator_id creatorId,title,description,group_name groupName,start_time startTime,deadline_time deadlineTime,status,approval_status approvalStatus,reviewed_by reviewedBy,reviewed_at reviewedAt,unassigned_count unassignedCount,created_at createdAt from team_task where id=? and deleted_at is null",
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
            return jdbc.queryForObject("select id,user_id userId,type,title,content,related_type relatedType,related_id relatedId,reminder_id reminderId,target_route targetRoute,data_revision dataRevision,is_read isRead,read_at readAt,created_at createdAt from notification where id=? and deleted_at is null",
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
    public Map<String, Object> createUser(long adminId, Map<String, Object> req, String ipAddress, String userAgent) {
        requireActiveAdmin(adminId);
        String email = EmailAddress.normalize(text(req.get("email")));
        String password = rawText(req.get("password"));
        String phone = normalizeOptionalPhone(text(req.get("phone")));
        String nickname = text(req.get("nickname"));
        String timezone = validateTimezone(text(req.get("timezone")));

        if (!validPassword(password)) throw new BusinessException(400, "password format is invalid");
        if (nickname.isBlank()) nickname = "User";
        if (nickname.length() > 50 || containsControlCharacter(nickname)) throw new BusinessException(400, "nickname is invalid");
        if (count("select count(*) from `user` where email=?", email) > 0) {
            throw new BusinessException(409, "email already registered");
        }
        if (phone != null && count("select count(*) from `user` where phone=?", phone) > 0) {
            throw new BusinessException(409, "phone already registered");
        }

        KeyHolder keyHolder = new GeneratedKeyHolder();
        String selectedNickname = nickname;
        try {
            jdbc.update(con -> {
                PreparedStatement ps = con.prepareStatement(
                        "insert into `user` (phone,email,email_verified_at,password_hash,nickname,avatar_url,timezone,status) "
                                + "values (?,?,utc_timestamp(),?,?,?,?,'active')",
                        new String[]{"id"});
                ps.setString(1, phone);
                ps.setString(2, email);
                ps.setString(3, passwordEncoder.encode(password));
                ps.setString(4, selectedNickname);
                ps.setString(5, "");
                ps.setString(6, timezone);
                return ps;
            }, keyHolder);
        } catch (DuplicateKeyException ex) {
            if (count("select count(*) from `user` where email=?", email) > 0) {
                throw new BusinessException(409, "email already registered");
            }
            throw new BusinessException(409, "phone already registered");
        }

        long createdId = Objects.requireNonNull(keyHolder.getKey()).longValue();
        createDefaultTaskGroups(createdId);
        Map<String, Object> after = userView(createdId);
        writeAdminOperationLog(adminId, "create_user", "user", createdId, null, after, ipAddress, userAgent);
        return after;
    }

    @Transactional
    public Map<String, Object> updateUser(long adminId, long userId, Map<String, Object> req,
                                          String ipAddress, String userAgent) {
        requireActiveAdmin(adminId);
        long expectedProfileVersion = requiredProfileVersion(req.get("profileVersion"));
        Map<String, Object> before = userViewForUpdate(userId);
        long currentProfileVersion = ((Number) before.get("profileVersion")).longValue();
        if (currentProfileVersion != expectedProfileVersion) {
            throw new BusinessException(409, "user information changed");
        }

        String rawEmail = text(req.get("email"));
        String email = rawEmail.isBlank() ? null : EmailAddress.normalize(rawEmail);
        String phone = normalizeOptionalPhone(text(req.get("phone")));
        String nickname = text(req.get("nickname"));
        String rawTimezone = text(req.get("timezone"));
        if (email == null && phone == null) {
            throw new BusinessException(400, "email or phone is required");
        }
        if (nickname.isBlank()) nickname = "User";
        if (nickname.length() > 50 || containsControlCharacter(nickname)) {
            throw new BusinessException(400, "nickname is invalid");
        }
        String timezone = validateTimezone(rawTimezone);

        String previousEmail = before.get("email") == null ? null : String.valueOf(before.get("email"));
        String previousPhone = before.get("phone") == null ? null : String.valueOf(before.get("phone"));
        String previousNickname = String.valueOf(before.get("nickname"));
        String previousTimezone = String.valueOf(before.get("timezone"));
        boolean emailChanged = !Objects.equals(previousEmail, email);
        boolean phoneChanged = !Objects.equals(previousPhone, phone);
        boolean nicknameChanged = !Objects.equals(previousNickname, nickname);
        boolean timezoneChanged = !Objects.equals(previousTimezone, timezone);
        boolean identityChanged = emailChanged || phoneChanged;
        if (identityChanged) requireSuperAdmin(adminId);
        if (!identityChanged && !nicknameChanged && !timezoneChanged) return before;

        if (email != null && count("select count(*) from `user` where email=? and id<>?", email, userId) > 0) {
            throw new BusinessException(409, "email already registered");
        }
        if (phone != null && count("select count(*) from `user` where phone=? and id<>?", phone, userId) > 0) {
            throw new BusinessException(409, "phone already registered");
        }

        String selectedNickname = nickname;
        try {
            StringBuilder updateSql = new StringBuilder(
                    "update `user` set email=?,phone=?,nickname=?,timezone=?");
            if (emailChanged) {
                updateSql.append(email == null
                        ? ",email_verified_at=null"
                        : ",email_verified_at=utc_timestamp()");
            }
            if (identityChanged) updateSql.append(",token_version=token_version+1");
            updateSql.append(",profile_version=profile_version+1 where id=? and deleted_at is null and profile_version=?");
            int updated = jdbc.update(updateSql.toString(), email, phone, selectedNickname, timezone,
                    userId, expectedProfileVersion);
            if (updated == 0) throw new BusinessException(409, "user information changed");
        } catch (DuplicateKeyException ex) {
            if (email != null && count("select count(*) from `user` where email=? and id<>?", email, userId) > 0) {
                throw new BusinessException(409, "email already registered");
            }
            if (phone != null && count("select count(*) from `user` where phone=? and id<>?", phone, userId) > 0) {
                throw new BusinessException(409, "phone already registered");
            }
            throw new BusinessException(409, "email or phone already registered");
        }

        if (identityChanged) {
            refreshTokenStore.invalidateAllForUser(userId);
            jdbc.update("update auth_email_otp set status='invalidated',invalidated_at=utc_timestamp(),updated_at=utc_timestamp() "
                    + "where user_id=? and status='issued'", userId);
        }
        if (timezoneChanged) {
            fatigueService.timezoneChanged(userId, previousTimezone, timezone);
        }
        Map<String, Object> after = userView(userId);
        writeAdminOperationLog(adminId, "update_user", "user", userId, before, after, ipAddress, userAgent);
        return after;
    }

    @Transactional
    public Map<String, Object> adminSetUserStatus(long adminId, long userId, String status, String ipAddress, String userAgent) {
        requireActiveAdmin(adminId);
        if (!List.of("active", "disabled").contains(status)) throw new BusinessException(400, "status is invalid");
        Map<String, Object> before = userViewForUpdate(userId);
        int updated = "disabled".equals(status)
                ? jdbc.update("update `user` set status=?,token_version=token_version+1 where id=? and deleted_at is null",
                        status, userId)
                : jdbc.update("update `user` set status=? where id=? and deleted_at is null", status, userId);
        if (updated == 0) throw new BusinessException(404, "user not found");
        if ("disabled".equals(status)) refreshTokenStore.invalidateAllForUser(userId);
        Map<String, Object> after = userView(userId);
        writeAdminOperationLog(adminId, "set_user_status", "user", userId, before, after, ipAddress, userAgent);
        return after;
    }

    @Transactional
    public Map<String, Object> createAdminUser(long adminId, Map<String, Object> req, String ipAddress, String userAgent) {
        requireSuperAdmin(adminId);
        String username = text(req.get("username"));
        String password = rawText(req.get("password"));
        String role = text(req.getOrDefault("role", "admin"));
        if (username.isBlank()) throw new BusinessException(400, "username is required");
        if (username.length() > 50 || containsControlCharacter(username)) throw new BusinessException(400, "username is invalid");
        if (!validPassword(password)) throw new BusinessException(400, "password format is invalid");
        if (!"admin".equals(role)) throw new BusinessException(400, "role is invalid");
        if (count("select count(*) from admin_user where username=?", username) > 0) throw new BusinessException(409, "admin username already exists");
        KeyHolder keyHolder = new GeneratedKeyHolder();
        try {
            jdbc.update(con -> {
                PreparedStatement ps = con.prepareStatement("insert into admin_user (username,password_hash,role,status) values (?,?,?, 'active')", new String[]{"id"});
                ps.setString(1, username);
                ps.setString(2, passwordEncoder.encode(password));
                ps.setString(3, role);
                return ps;
            }, keyHolder);
        } catch (DuplicateKeyException ex) {
            throw new BusinessException(409, "admin username already exists");
        }
        long createdId = Objects.requireNonNull(keyHolder.getKey()).longValue();
        Map<String, Object> after = adminView(createdId);
        writeAdminOperationLog(adminId, "create_admin_user", "admin_user", createdId, null, after, ipAddress, userAgent);
        return after;
    }

    @Transactional
    public Map<String, Object> adminSetAdminUserStatus(long adminId, long targetAdminId, String status, String ipAddress, String userAgent) {
        requireSuperAdmin(adminId);
        if (!List.of("active", "disabled").contains(status)) throw new BusinessException(400, "status is invalid");
        if (adminId == targetAdminId && "disabled".equals(status)) throw new BusinessException(400, "cannot disable yourself");
        Map<String, Object> before = adminView(targetAdminId);
        int updated = jdbc.update("update admin_user set status=? where id=?", status, targetAdminId);
        if (updated == 0) throw new BusinessException(404, "admin user not found");
        Map<String, Object> after = adminView(targetAdminId);
        writeAdminOperationLog(adminId, "set_admin_user_status", "admin_user", targetAdminId, before, after, ipAddress, userAgent);
        return after;
    }

    // ==================== Admin team task operations ====================

    @Transactional
    public Map<String, Object> adminCancelTeamTask(long adminId, long taskId, String ipAddress, String userAgent) {
        lockAdminTeamTask(taskId);
        Map<String, Object> task = adminTeamTaskDetail(taskId);
        String currentStatus = String.valueOf(task.get("status"));
        if (!List.of("active", "all_rejected", "unassigned", "pending_approval").contains(currentStatus)) {
            throw new BusinessException(400, "task cannot be cancelled in current status");
        }
        int updated = jdbc.update("update team_task set status='cancelled', updated_by=? where id=? and status=? and deleted_at is null", adminId, taskId, currentStatus);
        if (updated == 0) throw new BusinessException(409, "task status has changed");
        jdbc.update("insert into team_task_event (task_id, actor_id, actor_type, event_type, content) values (?,?,'admin',?,?)", taskId, adminId, "cancelled", "管理员取消了任务");
        jdbc.update("update reminder set status='paused' where target_type='team_task' and target_id=? and status='pending'", taskId);
        Map<String, Object> after = adminTeamTaskDetail(taskId);
        writeAdminOperationLog(adminId, "cancel_team_task", "team_task", taskId, task, after, ipAddress, userAgent);
        return after;
    }

    @Transactional
    public Map<String, Object> adminRestoreTeamTask(long adminId, long taskId, String ipAddress, String userAgent) {
        lockAdminTeamTask(taskId);
        Map<String, Object> task = adminTeamTaskDetail(taskId);
        if (!"cancelled".equals(task.get("status"))) {
            throw new BusinessException(400, "only cancelled task can be restored");
        }
        String approvalStatus = String.valueOf(task.getOrDefault("approvalStatus", "approved"));
        String provisionalStatus = "pending".equals(approvalStatus) ? "pending_approval" : "rejected".equals(approvalStatus) ? "approval_rejected" : "active";
        int updated = jdbc.update("update team_task set status=?, updated_by=? where id=? and status='cancelled' and deleted_at is null", provisionalStatus, adminId, taskId);
        if (updated == 0) throw new BusinessException(409, "task status has changed");
        recalculateAdminTeamTaskStatus(taskId);
        Map<String, Object> restored = adminTeamTaskDetail(taskId);
        if ("approved".equals(restored.get("approvalStatus")) && List.of("active", "unassigned").contains(String.valueOf(restored.get("status")))) {
            resumeAdminTeamTaskReminders(taskId);
            instantiateAdminTeamTaskReminderPlan(taskId);
        }
        jdbc.update("insert into team_task_event (task_id, actor_id, actor_type, event_type, content) values (?,?,'admin',?,?)", taskId, adminId, "restored", "管理员恢复了任务");
        Map<String, Object> after = adminTeamTaskDetail(taskId);
        writeAdminOperationLog(adminId, "restore_team_task", "team_task", taskId, task, after, ipAddress, userAgent);
        return after;
    }

    @Transactional
    public Map<String, Object> adminCorrectAssigneeStatus(long adminId, long taskId, long assigneeId, String status, String ipAddress, String userAgent) {
        if (!List.of("pending", "accepted", "rejected", "completed").contains(status)) throw new BusinessException(400, "status is invalid");
        lockAdminTeamTask(taskId);
        Map<String, Object> before = adminTeamTaskDetail(taskId);
        if (!"approved".equals(before.get("approvalStatus"))) throw new BusinessException(400, "task assignment is not approved");
        if ("completed".equals(before.get("status"))) throw new BusinessException(400, "completed task cannot be reopened");
        int updated = jdbc.update("update team_task_assignee set status=?, status_updated_by=?, status_updated_at=utc_timestamp() where id=? and task_id=? and is_active=true", status, adminId, assigneeId, taskId);
        if (updated == 0) throw new BusinessException(404, "assignee not found");
        recalculateAdminTeamTaskStatus(taskId);
        syncAdminAssigneeReminders(taskId, assigneeId, status);
        jdbc.update("insert into team_task_event (task_id, actor_id, actor_type, event_type, content) values (?,?,'admin',?,?)", taskId, adminId, "status_corrected", "管理员修正执行人状态为: " + statusLabel(status));
        Map<String, Object> after = adminTeamTaskDetail(taskId);
        writeAdminOperationLog(adminId, "correct_assignee_status", "team_task", taskId, before, after, ipAddress, userAgent);
        return after;
    }

    @Transactional
    public Map<String, Object> adminSetScheduleStatus(long adminId, long scheduleId, String status, String ipAddress, String userAgent) {
        if (!List.of("pending", "completed", "cancelled").contains(status)) throw new BusinessException(400, "status is invalid");
        Map<String, Object> before = adminScheduleDetail(scheduleId);
        String currentStatus = String.valueOf(before.get("status"));
        if (currentStatus.equals(status)) throw new BusinessException(400, "schedule already has requested status");
        boolean validTransition = "pending".equals(currentStatus)
                ? List.of("completed", "cancelled").contains(status)
                : "pending".equals(status) && List.of("completed", "cancelled").contains(currentStatus);
        if (!validTransition) throw new BusinessException(400, "invalid schedule status transition");
        int updated;
        if ("completed".equals(status)) {
            Integer fatigueLevel = jdbc.queryForObject("select fatigue_level from schedule where id=? and deleted_at is null", Integer.class, scheduleId);
            int level = fatigueLevel == null ? 3 : fatigueLevel;
            updated = jdbc.update("update schedule set status=?,completed_at=utc_timestamp(),completed_fatigue_level=?,completed_fatigue_weight=? where id=? and deleted_at is null",
                    status, level, fatigueService.currentWeight(longValue(before.get("userId")), level), scheduleId);
        } else if ("pending".equals(status)) {
            updated = jdbc.update("update schedule set status=?,completed_at=null,completed_fatigue_level=null,completed_fatigue_weight=null where id=? and deleted_at is null", status, scheduleId);
        } else {
            updated = jdbc.update("update schedule set status=? where id=? and deleted_at is null", status, scheduleId);
        }
        if (updated == 0) throw new BusinessException(404, "schedule not found");
        if (List.of("completed", "cancelled").contains(status)) {
            jdbc.update("update reminder set status='paused' where target_type='schedule' and target_id=? and status='pending'", scheduleId);
        } else {
            jdbc.update("update reminder set status='cancelled' where target_type='schedule' and target_id=? and status='paused' and remind_at<=utc_timestamp()", scheduleId);
            jdbc.update("update reminder set status='pending' where target_type='schedule' and target_id=? and status='paused' and remind_at>utc_timestamp()", scheduleId);
        }
        Map<String, Object> after = adminScheduleDetail(scheduleId);
        ZoneId zone = userZone(longValue(after.get("userId")));
        java.util.List<LocalDate> dates = new java.util.ArrayList<>();
        LocalDate planned = plannedDate(after, zone);
        if (planned != null) dates.add(planned);
        LocalDate completed = completedDate(before, zone);
        if (completed != null) dates.add(completed);
        completed = completedDate(after, zone);
        if (completed != null) dates.add(completed);
        if (!dates.isEmpty()) fatigueService.recalculateDates(longValue(after.get("userId")), dates, true);
        writeAdminOperationLog(adminId, "set_schedule_status", "schedule", scheduleId, before, after, ipAddress, userAgent);
        return after;
    }

    private void recalculateAdminTeamTaskStatus(long taskId) {
        Map<String, Object> task = jdbc.queryForMap("select status,approval_status approvalStatus,unassigned_count unassignedCount from team_task where id=? and deleted_at is null for update", taskId);
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
        boolean hasOpen = statuses.stream().anyMatch(value -> List.of("pending", "accepted").contains(value));
        boolean allRejected = statuses.stream().allMatch("rejected"::equals);
        jdbc.update("update team_task set status=? where id=?", hasOpen ? "active" : allRejected ? "all_rejected" : "completed", taskId);
    }

    private void lockAdminTeamTask(long taskId) {
        try {
            jdbc.queryForObject("select id from team_task where id=? and deleted_at is null for update", Long.class, taskId);
        } catch (EmptyResultDataAccessException ex) {
            throw new BusinessException(404, "team task not found");
        }
    }

    private void resumeAdminTeamTaskReminders(long taskId) {
        jdbc.update("update reminder set status='cancelled' where target_type='team_task' and target_id=? and status='paused' and remind_at<=utc_timestamp()", taskId);
        jdbc.update("update reminder set status='pending' where target_type='team_task' and target_id=? and status='paused' and remind_at>utc_timestamp()", taskId);
    }

    private void instantiateAdminTeamTaskReminderPlan(long taskId) {
        List<Long> assigneeUserIds = jdbc.queryForList("select user_id from team_task_assignee where task_id=? and is_active=true and status in ('pending','accepted')", Long.class, taskId);
        for (Long assigneeUserId : assigneeUserIds) instantiateAdminAssigneeReminderPlan(taskId, assigneeUserId);
    }

    private void instantiateAdminAssigneeReminderPlan(long taskId, long assigneeUserId) {
        List<Timestamp> remindTimes = jdbc.queryForList("select remind_at from team_task_reminder_plan where task_id=? and remind_at>utc_timestamp() order by remind_at", Timestamp.class, taskId);
        for (Timestamp remindAt : remindTimes) {
            jdbc.update("insert into reminder (user_id,target_type,target_id,remind_at,status) select ?,'team_task',?,?,'pending' where not exists (select 1 from reminder where user_id=? and target_type='team_task' and target_id=? and remind_at=? and status in ('pending','paused'))",
                    assigneeUserId, taskId, remindAt, assigneeUserId, taskId, remindAt);
        }
    }

    private void syncAdminAssigneeReminders(long taskId, long assigneeId, String status) {
        Long assigneeUserId = jdbc.queryForObject("select user_id from team_task_assignee where id=? and task_id=? and is_active=true", Long.class, assigneeId, taskId);
        if (assigneeUserId == null) return;
        if (List.of("rejected", "completed").contains(status)) {
            jdbc.update("update reminder set status='cancelled' where target_type='team_task' and target_id=? and user_id=? and status in ('pending','paused')", taskId, assigneeUserId);
        } else {
            String taskStatus = jdbc.queryForObject("select status from team_task where id=?", String.class, taskId);
            if (List.of("active", "unassigned").contains(taskStatus)) instantiateAdminAssigneeReminderPlan(taskId, assigneeUserId);
        }
        String taskStatus = jdbc.queryForObject("select status from team_task where id=?", String.class, taskId);
        if ("completed".equals(taskStatus)) {
            jdbc.update("update reminder set status='cancelled' where target_type='team_task' and target_id=? and status in ('pending','paused')", taskId);
        }
    }

    private static String statusLabel(String status) {
        return switch (status) {
            case "pending" -> "待接受";
            case "accepted" -> "已接受";
            case "rejected" -> "已拒绝";
            case "completed" -> "已完成";
            default -> status;
        };
    }

    // ==================== Operation logs ====================

    public Map<String, Object> adminOperationLogs(int page, int size, String adminId, String action, String targetType, String dateFrom, String dateTo, String keyword) {
        StringBuilder where = new StringBuilder(" from admin_operation_log where 1=1");
        List<Object> params = new ArrayList<>();
        if (!blank(adminId)) {
            where.append(" and admin_id=?");
            try {
                params.add(Long.parseLong(adminId));
            } catch (NumberFormatException ex) {
                throw new BusinessException(400, "adminId is invalid");
            }
        }
        if (!blank(action)) { where.append(" and action like ?"); params.add("%" + action + "%"); }
        if (!blank(targetType)) { where.append(" and target_type=?"); params.add(targetType); }
        if (!blank(keyword)) { where.append(" and (action like ? or target_type like ?)"); String kw = "%" + keyword + "%"; params.add(kw); params.add(kw); }
        if (!blank(dateFrom)) { where.append(" and created_at >= ?"); params.add(dateFrom + " 00:00:00"); }
        if (!blank(dateTo)) { where.append(" and created_at <= ?"); params.add(dateTo + " 23:59:59"); }

        Integer totalValue = jdbc.queryForObject("select count(*)" + where, Integer.class, params.toArray());
        int total = totalValue == null ? 0 : totalValue;
        int safePage = Math.max(1, page);
        int safeSize = Math.min(100, Math.max(1, size));
        String sql = "select id,admin_id adminId,action,target_type targetType,target_id targetId,before_data beforeData,after_data afterData,ip_address ipAddress,user_agent userAgent,created_at createdAt" +
                where + " order by created_at desc,id desc limit ? offset ?";
        params.add(safeSize);
        params.add((safePage - 1) * safeSize);

        List<Map<String, Object>> rows = jdbc.query(sql, (rs, i) -> {
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

        return pagedResult(rows, total, safePage, safeSize);
    }

    public void writeAdminOperationLog(long adminId, String action, String targetType, Long targetId, Map<String, Object> beforeData, Map<String, Object> afterData, String ipAddress, String userAgent) {
        jdbc.update("insert into admin_operation_log (admin_id,action,target_type,target_id,before_data,after_data,ip_address,user_agent) values (?,?,?,?,cast(? as json),cast(? as json),?,?)",
                adminId, action, targetType, targetId, toJson(beforeData), toJson(afterData), ipAddress, userAgent);
    }

    // ==================== Internal helpers ====================

    private Map<String, Object> normalizeAdminRow(Map<String, Object> raw) {
        Map<String, Object> out = new LinkedHashMap<>();
        raw.forEach((key, value) -> out.put(key, value instanceof Timestamp ts ? iso(ts) : value));
        if (out.containsKey("profileversion")) {
            out.put("profileVersion", out.remove("profileversion"));
        }
        return out;
    }

    public Map<String, Object> userView(long userId) {
        Map<String, Object> user = requireUserEntity(userId);
        user.remove("passwordHash");
        return user;
    }

    private Map<String, Object> userViewForUpdate(long userId) {
        Map<String, Object> user = requireUserEntityForUpdate(userId);
        user.remove("passwordHash");
        return user;
    }

    private Map<String, Object> requireUserEntity(long userId) {
        try {
            return jdbc.queryForObject("select id,phone,email,email_verified_at emailVerifiedAt,password_hash passwordHash,nickname,avatar_url avatarUrl,timezone,status,profile_version profileVersion,created_at createdAt from `user` where id=? and deleted_at is null", userMapper(), userId);
        } catch (EmptyResultDataAccessException ex) {
            throw new BusinessException(404, "user not found");
        }
    }

    private Map<String, Object> requireUserEntityForUpdate(long userId) {
        try {
            return jdbc.queryForObject("select id,phone,email,email_verified_at emailVerifiedAt,password_hash passwordHash,nickname,avatar_url avatarUrl,timezone,status,profile_version profileVersion,created_at createdAt from `user` where id=? and deleted_at is null for update",
                    userMapper(), userId);
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

    private static Map<String, Object> pagedResult(List<Map<String, Object>> rows, int total, int page, int size) {
        return Map.of("list", rows, "total", total, "page", page, "size", size);
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
            m.put("email", rs.getString("email"));
            m.put("emailVerifiedAt", iso(rs.getTimestamp("emailVerifiedAt")));
            m.put("passwordHash", rs.getString("passwordHash"));
            m.put("nickname", rs.getString("nickname"));
            m.put("avatarUrl", rs.getString("avatarUrl"));
            m.put("timezone", rs.getString("timezone"));
            m.put("status", rs.getString("status"));
            m.put("profileVersion", rs.getLong("profileVersion"));
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
            m.put("approvalStatus", rs.getString("approvalStatus"));
            m.put("reviewedBy", rs.getObject("reviewedBy"));
            m.put("reviewedAt", iso(rs.getTimestamp("reviewedAt")));
            m.put("unassignedCount", rs.getInt("unassignedCount"));
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
            m.put("urgencyLevel", rs.getInt("urgencyLevel"));
            m.put("fatigueLevel", rs.getInt("fatigueLevel"));
            m.put("completedAt", iso(rs.getTimestamp("completedAt")));
            m.put("completedFatigueLevel", rs.getObject("completedFatigueLevel"));
            m.put("completedFatigueWeight", rs.getObject("completedFatigueWeight"));
            m.put("createdAt", iso(rs.getTimestamp("createdAt")));
            return m;
        };
    }

    private static int fatigueWeight(int level) {
        return switch (level) {
            case 1 -> 1;
            case 2 -> 2;
            case 3 -> 3;
            case 4 -> 5;
            case 5 -> 8;
            default -> 3;
        };
    }

    private ZoneId userZone(long userId) {
        String timezone = jdbc.queryForObject("select timezone from `user` where id=? and deleted_at is null", String.class, userId);
        try { return ZoneId.of(timezone == null ? "Asia/Shanghai" : timezone); } catch (Exception ignored) { return ZoneId.of("Asia/Shanghai"); }
    }

    private static LocalDate plannedDate(Map<String, Object> item, ZoneId zone) {
        String type = String.valueOf(item.getOrDefault("timeType", ""));
        String value = switch (type) {
            case "deadline_task" -> String.valueOf(item.getOrDefault("deadlineTime", ""));
            case "duration_task", "point_event" -> String.valueOf(item.getOrDefault("startTime", ""));
            default -> "";
        };
        try { return OffsetDateTime.parse(value).atZoneSameInstant(zone).toLocalDate(); } catch (Exception ignored) { return null; }
    }

    private static LocalDate completedDate(Map<String, Object> item, ZoneId zone) {
        try { return OffsetDateTime.parse(String.valueOf(item.getOrDefault("completedAt", ""))).atZoneSameInstant(zone).toLocalDate(); } catch (Exception ignored) { return null; }
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
            m.put("targetRoute", rs.getString("targetRoute"));
            m.put("dataRevision", rs.getObject("dataRevision"));
            m.put("isRead", rs.getBoolean("isRead"));
            m.put("readAt", iso(rs.getTimestamp("readAt")));
            m.put("createdAt", iso(rs.getTimestamp("createdAt")));
            return m;
        };
    }

    private String toJson(Map<String, Object> data) {
        if (data == null) return null;
        try {
            return objectMapper.writeValueAsString(data);
        } catch (JsonProcessingException ex) {
            throw new IllegalStateException("failed to serialize admin operation log", ex);
        }
    }

    private static String iso(Timestamp ts) {
        return ts == null ? "" : OffsetDateTime.ofInstant(ts.toInstant(), ZoneOffset.UTC).toString();
    }

    private static boolean blank(String s) {
        return s == null || s.isBlank();
    }

    private Map<String, Object> requireActiveAdmin(long adminId) {
        Map<String, Object> admin = requireAdminEntity(adminId);
        if (!"active".equals(admin.get("status"))) throw new BusinessException(403, "admin permission is required");
        return admin;
    }

    private void createDefaultTaskGroups(long userId) {
        String[] names = {"工作", "生活", "团队"};
        for (int i = 0; i < names.length; i++) {
            jdbc.update("insert into task_group (user_id,scope,name,sort_order,is_default) values (?,'personal',?,?,?)",
                    userId, names[i], (i + 1) * 10, i == 0);
        }
    }

    private static String normalizeOptionalPhone(String raw) {
        if (blank(raw)) return null;
        String phone = raw.trim();
        if (!phone.matches("1[3-9]\\d{9}")) throw new BusinessException(400, "phone format is invalid");
        return phone;
    }

    private static boolean validPassword(String password) {
        return password != null
                && password.length() >= 8
                && password.getBytes(StandardCharsets.UTF_8).length <= 72
                && password.matches(".*[A-Za-z].*")
                && password.matches(".*\\d.*");
    }

    private static String validateTimezone(String timezone) {
        String selected = blank(timezone) ? "Asia/Shanghai" : timezone.trim();
        try {
            ZoneId.of(selected);
        } catch (DateTimeException ex) {
            throw new BusinessException(400, "timezone is invalid");
        }
        return selected;
    }

    private static String text(Object value) {
        return value == null ? "" : String.valueOf(value).trim();
    }

    private static String rawText(Object value) {
        return value == null ? "" : String.valueOf(value);
    }

    private static boolean containsControlCharacter(String value) {
        return value.codePoints().anyMatch(Character::isISOControl);
    }

    private static long requiredProfileVersion(Object value) {
        if (!(value instanceof Byte || value instanceof Short || value instanceof Integer || value instanceof Long)) {
            throw new BusinessException(400, "profileVersion must be a non-negative integer");
        }
        long version = ((Number) value).longValue();
        if (version < 0) throw new BusinessException(400, "profileVersion must be a non-negative integer");
        return version;
    }

    private static long longValue(Object v) {
        return ((Number) v).longValue();
    }

    private Integer count(String sql, Object... args) {
        Integer n = jdbc.queryForObject(sql, Integer.class, args);
        return n == null ? 0 : n;
    }
}
