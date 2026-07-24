package com.dayliane.common;

import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;

import java.sql.PreparedStatement;
import java.sql.Statement;
import java.sql.Timestamp;
import java.time.*;
import java.util.*;
import java.util.Base64;

@Component
public class DbStore {
    private final JdbcTemplate jdbc;
    private final NamedParameterJdbcTemplate named;
    private final BCryptPasswordEncoder passwordEncoder = new BCryptPasswordEncoder();
    private final String jwtSecret;

    public DbStore(JdbcTemplate jdbc, NamedParameterJdbcTemplate named, org.springframework.core.env.Environment env) {
        this.jdbc = jdbc;
        this.named = named;
        this.jwtSecret = env.getProperty("app.jwt.secret", "change-me-in-development");
    }

    public long requireUser(String authorization) {
        if (authorization == null || !authorization.startsWith("Bearer ")) throw new BusinessException(401, "unauthorized");
        return verifyToken(authorization.substring(7), "access");
    }

    public String issueAccessToken(long userId) {
        return issueToken(userId, "access", 86_400);
    }

    public String issueRefreshToken(long userId) {
        return issueToken(userId, "refresh", 604_800);
    }

    public Long refreshUserId(String refreshToken) {
        return verifyToken(refreshToken, "refresh");
    }

    public void revokeAccessToken(String authorization) {
        // MVP uses stateless JWTs. A deny-list can be added when shared cache storage is introduced.
    }


    private String issueToken(long userId, String type, long ttlSeconds) {
        long expiresAt = Instant.now().getEpochSecond() + ttlSeconds;
        String header = base64Url("{\"alg\":\"HS256\",\"typ\":\"JWT\"}");
        String payload = base64Url("{\"sub\":" + userId + ",\"typ\":\"" + type + "\",\"exp\":" + expiresAt + "}");
        String unsigned = header + "." + payload;
        return unsigned + "." + sign(unsigned);
    }

    private long verifyToken(String token, String expectedType) {
        try {
            String[] parts = token.split("\\.");
            if (parts.length != 3) throw new IllegalArgumentException("bad token");
            String unsigned = parts[0] + "." + parts[1];
            if (!Objects.equals(parts[2], sign(unsigned))) throw new IllegalArgumentException("bad signature");
            String payload = new String(Base64.getUrlDecoder().decode(parts[1]), StandardCharsets.UTF_8);
            String type = jsonValue(payload, "typ");
            long exp = Long.parseLong(jsonValue(payload, "exp"));
            long sub = Long.parseLong(jsonValue(payload, "sub"));
            if (!expectedType.equals(type) || exp < Instant.now().getEpochSecond()) throw new IllegalArgumentException("expired token");
            return sub;
        } catch (Exception ex) {
            throw new BusinessException(401, "unauthorized");
        }
    }

    private String sign(String value) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(jwtSecret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            return Base64.getUrlEncoder().withoutPadding().encodeToString(mac.doFinal(value.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception ex) {
            throw new BusinessException(500, "token signing failed");
        }
    }

    private static String base64Url(String value) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(value.getBytes(StandardCharsets.UTF_8));
    }

    private static String jsonValue(String json, String key) {
        String marker = "\"" + key + "\":";
        int start = json.indexOf(marker);
        if (start < 0) throw new IllegalArgumentException("missing claim");
        start += marker.length();
        if (json.charAt(start) == '\"') {
            int end = json.indexOf('\"', start + 1);
            return json.substring(start + 1, end);
        }
        int end = start;
        while (end < json.length() && Character.isDigit(json.charAt(end))) end++;
        return json.substring(start, end);
    }
    @Transactional
    public long register(String phone, String password, String nickname, String timezone) {
        if (phone == null || phone.isBlank() || password == null || password.length() < 8 || !password.matches(".*[A-Za-z].*") || !password.matches(".*\\d.*")) {
            throw new BusinessException(400, "phone or password format is invalid");
        }
        if (count("select count(*) from `user` where phone = ?", phone) > 0) throw new BusinessException(409, "phone already registered");
        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbc.update(con -> {
            PreparedStatement ps = con.prepareStatement("insert into `user` (phone,password_hash,nickname,avatar_url,timezone,status) values (?,?,?,?,?, 'active')", Statement.RETURN_GENERATED_KEYS);
            ps.setString(1, phone);
            ps.setString(2, passwordEncoder.encode(password));
            ps.setString(3, blank(nickname) ? "User" : nickname);
            ps.setString(4, "");
            ps.setString(5, blank(timezone) ? "Asia/Shanghai" : timezone);
            return ps;
        }, keyHolder);
        return Objects.requireNonNull(keyHolder.getKey()).longValue();
    }

    public long login(String phone, String password) {
        Map<String, Object> user = findUserByPhone(phone);
        if (user == null || !"active".equals(user.get("status"))) throw new BusinessException(401, "phone or password is incorrect");
        String hash = String.valueOf(user.get("passwordHash"));
        if (!passwordEncoder.matches(password, hash) && !Objects.equals(password, hash)) throw new BusinessException(401, "phone or password is incorrect");
        return longValue(user.get("id"));
    }

    public Map<String, Object> userView(long userId) {
        Map<String, Object> user = requireUserEntity(userId);
        user.remove("passwordHash");
        return user;
    }

    public Map<String, Object> requireUserEntity(long userId) {
        try {
            return jdbc.queryForObject("select id, phone, password_hash passwordHash, nickname, avatar_url avatarUrl, timezone, status, created_at createdAt from `user` where id = ? and deleted_at is null", userMapper(), userId);
        } catch (EmptyResultDataAccessException ex) {
            throw new BusinessException(404, "user not found");
        }
    }

    public void updateUserProfile(long userId, Map<String, Object> req) {
        jdbc.update("update `user` set nickname = coalesce(?, nickname), avatar_url = coalesce(?, avatar_url) where id = ? and deleted_at is null",
                nullableText(req.get("nickname")), nullableText(req.get("avatarUrl")), userId);
    }

    public void updatePassword(long userId, String oldPassword, String newPassword) {
        Map<String, Object> user = requireUserEntity(userId);
        String hash = String.valueOf(user.get("passwordHash"));
        if (!passwordEncoder.matches(oldPassword, hash) && !Objects.equals(oldPassword, hash)) throw new BusinessException(400, "old password is incorrect");
        if (newPassword == null || newPassword.length() < 8 || !newPassword.matches(".*[A-Za-z].*") || !newPassword.matches(".*\\d.*")) throw new BusinessException(400, "new password format is invalid");
        jdbc.update("update `user` set password_hash = ? where id = ?", passwordEncoder.encode(newPassword), userId);
    }

    public void updateTimezone(long userId, String timezone) {
        jdbc.update("update `user` set timezone = ? where id = ?", blank(timezone) ? "Asia/Shanghai" : timezone, userId);
    }

    @Transactional
    public Map<String, Object> createSchedule(long userId, Map<String, Object> req) {
        String title = text(req, "title");
        String timeType = textOr(req, "timeType", "deadline_task");
        if (title.isBlank()) throw new BusinessException(400, "title is required");
        if (!List.of("point_event", "deadline_task").contains(timeType)) throw new BusinessException(400, "timeType is invalid");
        if ("point_event".equals(timeType) && blank(text(req, "startTime"))) throw new BusinessException(400, "startTime is required");
        if ("deadline_task".equals(timeType) && blank(text(req, "deadlineTime"))) throw new BusinessException(400, "deadlineTime is required");
        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbc.update(con -> {
            PreparedStatement ps = con.prepareStatement("insert into schedule (user_id,title,description,group_name,time_type,start_time,end_time,deadline_time,status) values (?,?,?,?,?,?,?,?, 'pending')", Statement.RETURN_GENERATED_KEYS);
            ps.setLong(1, userId);
            ps.setString(2, title);
            ps.setString(3, text(req, "description"));
            ps.setString(4, textOr(req, "groupName", "Default"));
            ps.setString(5, timeType);
            ps.setTimestamp(6, parseTime(text(req, "startTime")));
            ps.setTimestamp(7, parseTime(text(req, "endTime")));
            ps.setTimestamp(8, parseTime(text(req, "deadlineTime")));
            return ps;
        }, keyHolder);
        long id = Objects.requireNonNull(keyHolder.getKey()).longValue();
        createScheduleReminders(userId, id, req);
        return requireSchedule(id, userId);
    }

    public Map<String, Object> listSchedules(long userId, int page, int size, String status, String groupName) {
        StringBuilder sql = new StringBuilder("select id,user_id userId,title,description,group_name groupName,time_type timeType,start_time startTime,end_time endTime,deadline_time deadlineTime,status,created_at createdAt from schedule where user_id=:userId and deleted_at is null");
        MapSqlParameterSource p = new MapSqlParameterSource("userId", userId);
        if (!blank(status)) { sql.append(" and status=:status"); p.addValue("status", status); }
        if (!blank(groupName)) { sql.append(" and group_name=:groupName"); p.addValue("groupName", groupName); }
        sql.append(" order by coalesce(deadline_time,start_time,created_at) asc");
        return pageResult(named.query(sql.toString(), p, scheduleMapper()), page, size);
    }

    public Map<String, Object> requireSchedule(long id, long userId) {
        try {
            Map<String, Object> item = jdbc.queryForObject("select id,user_id userId,title,description,group_name groupName,time_type timeType,start_time startTime,end_time endTime,deadline_time deadlineTime,status,created_at createdAt from schedule where id=? and user_id=? and deleted_at is null", scheduleMapper(), id, userId);
            item.put("hasReminder", count("select count(*) from reminder where target_type='schedule' and target_id=?", id) > 0);
            return item;
        } catch (EmptyResultDataAccessException ex) {
            throw new BusinessException(404, "schedule not found");
        }
    }

    @Transactional
    public Map<String, Object> updateSchedule(long id, long userId, Map<String, Object> req) {
        requireSchedule(id, userId);
        jdbc.update("update schedule set title=coalesce(?,title), description=coalesce(?,description), group_name=coalesce(?,group_name), time_type=coalesce(?,time_type), start_time=coalesce(?,start_time), end_time=coalesce(?,end_time), deadline_time=coalesce(?,deadline_time) where id=? and user_id=?",
                nullableText(req.get("title")), nullableText(req.get("description")), nullableText(req.get("groupName")), nullableText(req.get("timeType")), parseOptional(req.get("startTime")), parseOptional(req.get("endTime")), parseOptional(req.get("deadlineTime")), id, userId);
        return requireSchedule(id, userId);
    }

    public void deleteSchedule(long id, long userId) {
        requireSchedule(id, userId);
        jdbc.update("update schedule set deleted_at=utc_timestamp(), deleted_by=? where id=?", userId, id);
    }

    @Transactional
    public Map<String, Object> setScheduleStatus(long id, long userId, String status) {
        requireSchedule(id, userId);
        jdbc.update("update schedule set status=? where id=? and user_id=?", status, id, userId);
        if (List.of("completed", "cancelled").contains(status)) cancelPendingReminders("schedule", id, null);
        return Map.of("id", id, "status", status);
    }

    public Map<String, Object> pageResult(List<Map<String, Object>> rows, int page, int size) {
        int p = Math.max(1, page);
        int s = Math.min(100, Math.max(1, size));
        int from = Math.min(rows.size(), (p - 1) * s);
        int to = Math.min(rows.size(), from + s);
        return Map.of("list", rows.subList(from, to), "total", rows.size(), "page", p, "size", s);
    }

    public Map<String, Object> listNotifications(long userId, int page, int size, Boolean isRead) {
        String sql = "select id,user_id userId,type,title,content,related_type relatedType,related_id relatedId,reminder_id reminderId,is_read isRead,read_at readAt,created_at createdAt from notification where user_id=:userId and deleted_at is null";
        MapSqlParameterSource p = new MapSqlParameterSource("userId", userId);
        if (isRead != null) {
            sql += " and is_read=:isRead";
            p.addValue("isRead", isRead);
        }
        sql += " order by created_at desc";
        return pageResult(named.query(sql, p, notificationMapper()), page, size);
    }

    public long unreadCount(long userId) {
        return count("select count(*) from notification where user_id=? and is_read=false and deleted_at is null", userId);
    }

    public void readNotification(long id, long userId) {
        int updated = jdbc.update("update notification set is_read=true, read_at=utc_timestamp() where id=? and user_id=? and deleted_at is null", id, userId);
        if (updated == 0) throw new BusinessException(404, "notification not found");
    }

    public void readAll(long userId) {
        jdbc.update("update notification set is_read=true, read_at=utc_timestamp() where user_id=? and deleted_at is null", userId);
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

    @SuppressWarnings("unchecked")
    public Map<String, Object> homeToday(long userId) {
        LocalDate today = LocalDate.now();
        List<Map<String, Object>> schedules = (List<Map<String, Object>>) listSchedules(userId, 1, 100, null, null).get("list");
        List<Map<String, Object>> todaySchedules = schedules.stream().filter(s -> sameDate(primaryTime(s), today)).toList();
        return Map.of(
                "personalSchedules", todaySchedules,
                "teamTasks", List.of(),
                "unreadNotificationCount", unreadCount(userId),
                "groups", List.of()
        );
    }

    @SuppressWarnings("unchecked")
    public Map<String, Object> homeUpcoming(long userId) {
        List<Map<String, Object>> schedules = (List<Map<String, Object>>) listSchedules(userId, 1, 100, "pending", null).get("list");
        List<Map<String, Object>> out = new ArrayList<>();
        for (Map<String, Object> s : schedules) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("type", "schedule");
            row.put("id", s.get("id"));
            row.put("title", s.get("title"));
            row.put("deadlineTime", primaryTime(s));
            row.put("groupName", s.get("groupName"));
            out.add(row);
        }
        out.sort(Comparator.comparing(x -> String.valueOf(x.get("deadlineTime"))));
        return Map.of("list", out.stream().limit(20).toList());
    }
    @Transactional
    public Map<String, Object> createTeam(long userId, String name) {
        if (blank(name)) throw new BusinessException(400, "team name is required");
        String code = inviteCode();
        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbc.update(con -> {
            PreparedStatement ps = con.prepareStatement("insert into team (name,invite_code,invite_code_expire_at,owner_id,status) values (?,?,date_add(utc_timestamp(), interval 15 day),?, 'active')", Statement.RETURN_GENERATED_KEYS);
            ps.setString(1, name.trim());
            ps.setString(2, code);
            ps.setLong(3, userId);
            return ps;
        }, keyHolder);
        long teamId = Objects.requireNonNull(keyHolder.getKey()).longValue();
        jdbc.update("insert into team_member (team_id,user_id,role,status,joined_at) values (?,?, 'owner','active',utc_timestamp())", teamId, userId);
        return teamView(teamId, userId);
    }

    public Map<String, Object> listTeams(long userId, int page, int size) {
        List<Map<String, Object>> rows = jdbc.query("select t.id from team t join team_member m on m.team_id=t.id where m.user_id=? and m.status='active' and t.deleted_at is null order by t.created_at desc", (rs, i) -> teamView(rs.getLong("id"), userId), userId);
        return pageResult(rows, page, size);
    }

    public Map<String, Object> teamDetail(long teamId, long userId) {
        requireActiveMember(teamId, userId);
        Map<String, Object> team = teamView(teamId, userId);
        team.put("members", activeMembers(teamId));
        return team;
    }

    @Transactional
    public Map<String, Object> joinTeam(long userId, String inviteCode) {
        Long teamId;
        try {
            teamId = jdbc.queryForObject("select id from team where invite_code=? and invite_code_expire_at > utc_timestamp() and deleted_at is null", Long.class, inviteCode.trim().toUpperCase());
        } catch (EmptyResultDataAccessException ex) {
            throw new BusinessException(404, "invite code not found");
        }
        if (isActiveMember(teamId, userId)) throw new BusinessException(409, "already joined team");
        if (count("select count(*) from team_member where team_id=? and user_id=?", teamId, userId) > 0) {
            jdbc.update("update team_member set status='active', role='member', joined_at=utc_timestamp(), removed_at=null, removed_by=null where team_id=? and user_id=?", teamId, userId);
        } else {
            jdbc.update("insert into team_member (team_id,user_id,role,status,joined_at) values (?,?, 'member','active',utc_timestamp())", teamId, userId);
        }
        return teamView(teamId, userId);
    }

    public List<Map<String, Object>> activeMembers(long teamId) {
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

    public void changeRole(long teamId, long targetUserId, long operatorId, String newRole) {
        if (!"owner".equals(role(teamId, operatorId))) throw new BusinessException(403, "only owner can change role");
        if (!List.of("admin", "member").contains(newRole)) throw new BusinessException(400, "role is invalid");
        if (longValue(requireTeam(teamId).get("ownerId")) == targetUserId) throw new BusinessException(400, "owner role cannot be changed");
        jdbc.update("update team_member set role=? where team_id=? and user_id=? and status='active'", newRole, teamId, targetUserId);
    }

    @Transactional
    public void removeMember(long teamId, long targetUserId, long operatorId) {
        String operatorRole = role(teamId, operatorId);
        String targetRole = role(teamId, targetUserId);
        if ("owner".equals(targetRole)) throw new BusinessException(403, "owner cannot be removed");
        if (!"owner".equals(operatorRole) && !("admin".equals(operatorRole) && "member".equals(targetRole))) throw new BusinessException(403, "no permission to remove member");
        jdbc.update("update team_member set status='removed', removed_at=utc_timestamp(), removed_by=? where team_id=? and user_id=?", operatorId, teamId, targetUserId);
        jdbc.update("update team_task_assignee set is_active=false where user_id=? and task_id in (select id from team_task where team_id=?) and is_active=true and status <> 'completed'", targetUserId, teamId);
    }

    public Map<String, Object> regenerateInvite(long teamId, long userId) {
        Map<String, Object> team = requireTeam(teamId);
        if (longValue(team.get("ownerId")) != userId) throw new BusinessException(403, "only owner can regenerate invite code");
        jdbc.update("update team set invite_code=?, invite_code_expire_at=date_add(utc_timestamp(), interval 15 day) where id=?", inviteCode(), teamId);
        return teamView(teamId, userId);
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

    private Map<String, Object> teamView(long teamId, long userId) {
        Map<String, Object> team = requireTeam(teamId);
        team.put("role", role(teamId, userId));
        team.put("memberCount", count("select count(*) from team_member where team_id=? and status='active'", teamId));
        team.put("activeTaskCount", count("select count(*) from team_task where team_id=? and status='active' and deleted_at is null", teamId));
        return team;
    }

    private void requireActiveMember(long teamId, long userId) {
        if (!isActiveMember(teamId, userId)) throw new BusinessException(403, "not a team member");
    }

    private boolean isActiveMember(long teamId, long userId) {
        return count("select count(*) from team_member where team_id=? and user_id=? and status='active'", teamId, userId) > 0;
    }

    private String role(long teamId, long userId) {
        try {
            return jdbc.queryForObject("select role from team_member where team_id=? and user_id=? and status='active'", String.class, teamId, userId);
        } catch (EmptyResultDataAccessException ex) {
            throw new BusinessException(403, "not a team member");
        }
    }

    private String inviteCode() {
        return UUID.randomUUID().toString().replace("-", "").substring(0, 6).toUpperCase();
    }
    @Transactional
    public Map<String, Object> createTeamTask(long userId, Map<String, Object> req) {
        long teamId = number(req.get("teamId"));
        requireActiveMember(teamId, userId);
        String title = text(req, "title");
        if (title.isBlank()) throw new BusinessException(400, "title is required");
        Object raw = req.get("assigneeUserIds");
        List<?> assigneeIds = raw instanceof List<?> list ? list : List.of();
        if (assigneeIds.isEmpty()) throw new BusinessException(400, "assigneeUserIds is required");
        for (Object v : assigneeIds) requireActiveMember(teamId, number(v));
        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbc.update(con -> {
            PreparedStatement ps = con.prepareStatement("insert into team_task (team_id,creator_id,title,description,group_name,start_time,deadline_time,status,updated_by) values (?,?,?,?,?,?,?, 'active',?)", Statement.RETURN_GENERATED_KEYS);
            ps.setLong(1, teamId);
            ps.setLong(2, userId);
            ps.setString(3, title);
            ps.setString(4, text(req, "description"));
            ps.setString(5, textOr(req, "groupName", "Team Task"));
            ps.setTimestamp(6, parseTime(text(req, "startTime")));
            ps.setTimestamp(7, parseTime(text(req, "deadlineTime")));
            ps.setLong(8, userId);
            return ps;
        }, keyHolder);
        long taskId = Objects.requireNonNull(keyHolder.getKey()).longValue();
        for (Object v : assigneeIds) {
            long assigneeUserId = number(v);
            jdbc.update("insert into team_task_assignee (task_id,user_id,assign_round,is_active,status,assigned_by,assigned_at) values (?,?,1,true,'pending',?,utc_timestamp())", taskId, assigneeUserId, userId);
            jdbc.update("insert into notification (user_id,type,title,content,related_type,related_id,is_read) values (?,?,?,?,?,?,false)", assigneeUserId, "task_assigned", "New team task", "You have been assigned: " + title, "team_task", taskId);
        }
        return teamTaskSummary(taskId, userId);
    }

    public Map<String, Object> listMyTeamTasks(long userId, int page, int size, String assignStatus) {
        String sql = "select t.id from team_task t join team_task_assignee a on a.task_id=t.id join team_member m on m.team_id=t.team_id and m.user_id=? and m.status='active' where a.user_id=? and a.is_active=true and t.deleted_at is null";
        Object[] args;
        if (blank(assignStatus)) {
            args = new Object[]{userId, userId};
        } else {
            sql += " and a.status=?";
            args = new Object[]{userId, userId, assignStatus};
        }
        sql += " order by t.deadline_time asc";
        List<Map<String, Object>> rows = jdbc.query(sql, (rs, i) -> teamTaskSummary(rs.getLong("id"), userId), args);
        return pageResult(rows, page, size);
    }

    public Map<String, Object> listTeamTasks(long teamId, long userId, int page, int size, String status) {
        requireActiveMember(teamId, userId);
        String sql = "select id from team_task where team_id=? and deleted_at is null";
        Object[] args;
        if (blank(status)) {
            args = new Object[]{teamId};
        } else {
            sql += " and status=?";
            args = new Object[]{teamId, status};
        }
        sql += " order by deadline_time asc";
        List<Map<String, Object>> rows = jdbc.query(sql, (rs, i) -> teamTaskSummary(rs.getLong("id"), userId), args);
        return pageResult(rows, page, size);
    }

    public Map<String, Object> teamTaskDetail(long taskId, long userId) {
        Map<String, Object> task = requireTeamTask(taskId);
        requireActiveMember(longValue(task.get("teamId")), userId);
        task.put("assignees", teamTaskAssignees(taskId));
        task.put("creator", userView(longValue(task.get("creatorId"))));
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
        recalculateTeamTaskStatus(taskId);
        return teamTaskDetail(taskId, userId);
    }

    public Map<String, Object> updateTeamTask(long taskId, long userId, Map<String, Object> req) {
        requireTeamTaskManager(taskId, userId);
        jdbc.update("update team_task set title=coalesce(?,title), description=coalesce(?,description), group_name=coalesce(?,group_name), updated_by=? where id=?",
                nullableText(req.get("title")), nullableText(req.get("description")), nullableText(req.get("groupName")), userId, taskId);
        return teamTaskDetail(taskId, userId);
    }

    public Map<String, Object> updateTeamTaskTime(long taskId, long userId, Map<String, Object> req) {
        requireTeamTaskManager(taskId, userId);
        jdbc.update("update team_task set start_time=coalesce(?,start_time), deadline_time=coalesce(?,deadline_time), time_updated_at=utc_timestamp(), updated_by=? where id=?",
                parseOptional(req.get("startTime")), parseOptional(req.get("deadlineTime")), userId, taskId);
        return teamTaskDetail(taskId, userId);
    }

    public void deleteTeamTask(long taskId, long userId) {
        requireTeamTaskManager(taskId, userId);
        jdbc.update("update team_task set deleted_at=utc_timestamp(), deleted_by=? where id=?", userId, taskId);
    }

    @Transactional
    public Map<String, Object> cancelTeamTask(long taskId, long userId) {
        requireTeamTaskManager(taskId, userId);
        jdbc.update("update team_task set status='cancelled', updated_by=? where id=?", userId, taskId);
        cancelPendingReminders("team_task", taskId, null);
        return teamTaskSummary(taskId, userId);
    }

    @Transactional
    public Map<String, Object> restoreTeamTask(long taskId, long userId) {
        Map<String, Object> task = requireTeamTask(taskId);
        if (!canManageTeam(longValue(task.get("teamId")), userId)) throw new BusinessException(403, "only team manager can restore task");
        jdbc.update("update team_task set status='active', updated_by=? where id=?", userId, taskId);
        recalculateTeamTaskStatus(taskId);
        return teamTaskSummary(taskId, userId);
    }

    @Transactional
    public Map<String, Object> reassignTeamTask(long taskId, long userId, long originalUserId, long newUserId) {
        requireTeamTaskManager(taskId, userId);
        Map<String, Object> task = requireTeamTask(taskId);
        long teamId = longValue(task.get("teamId"));
        requireActiveMember(teamId, newUserId);
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
        jdbc.update("insert into notification (user_id,type,title,content,related_type,related_id,is_read) values (?,?,?,?,?,?,false)",
                newUserId, "task_assigned", "Task reassigned", "You have been assigned: " + task.get("title"), "team_task", taskId);
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

    private void requireTeamTaskManager(long taskId, long userId) {
        Map<String, Object> task = requireTeamTask(taskId);
        long teamId = longValue(task.get("teamId"));
        requireActiveMember(teamId, userId);
        if (longValue(task.get("creatorId")) != userId && !canManageTeam(teamId, userId)) throw new BusinessException(403, "no permission to manage task");
    }

    private boolean canManageTeam(long teamId, long userId) {
        String r = role(teamId, userId);
        return "owner".equals(r) || "admin".equals(r);
    }
    private Map<String, Object> requireTeamTask(long taskId) {
        try {
            return jdbc.queryForObject("select id,team_id teamId,creator_id creatorId,title,description,group_name groupName,start_time startTime,deadline_time deadlineTime,status,created_at createdAt from team_task where id=? and deleted_at is null", teamTaskMapper(), taskId);
        } catch (EmptyResultDataAccessException ex) {
            throw new BusinessException(404, "team task not found");
        }
    }

    private Map<String, Object> teamTaskSummary(long taskId, long userId) {
        Map<String, Object> task = requireTeamTask(taskId);
        task.put("teamName", requireTeam(longValue(task.get("teamId"))).get("name"));
        task.put("assigneeCount", count("select count(*) from team_task_assignee where task_id=? and is_active=true", taskId));
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
            m.put("groupName", rs.getString("groupName"));
            m.put("startTime", iso(rs.getTimestamp("startTime")));
            m.put("deadlineTime", iso(rs.getTimestamp("deadlineTime")));
            m.put("status", rs.getString("status"));
            m.put("createdAt", iso(rs.getTimestamp("createdAt")));
            return m;
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
    public Map<String, Object> adminList(String table, int page, int size) {
        String sql = switch (table) {
            case "users" -> "select id,phone,nickname,timezone,status,created_at createdAt from `user` order by created_at desc";
            case "teams" -> "select id,name,invite_code inviteCode,owner_id ownerId,status,created_at createdAt from team order by created_at desc";
            case "schedules" -> "select id,user_id userId,title,group_name groupName,time_type timeType,status,created_at createdAt from schedule order by created_at desc";
            case "teamTasks" -> "select id,team_id teamId,creator_id creatorId,title,status,deadline_time deadlineTime,created_at createdAt from team_task order by created_at desc";
            case "notifications" -> "select id,user_id userId,type,title,related_type relatedType,related_id relatedId,is_read isRead,created_at createdAt from notification order by created_at desc";
            case "reminders" -> "select id,user_id userId,target_type targetType,target_id targetId,remind_at remindAt,status,created_at createdAt from reminder order by created_at desc";
            default -> throw new BusinessException(404, "admin resource not found");
        };
        List<Map<String, Object>> rows = jdbc.queryForList(sql).stream().map(this::normalizeAdminRow).toList();
        return pageResult(rows, page, size);
    }

    public Map<String, Object> adminSetUserStatus(long userId, String status) {
        if (!List.of("active", "disabled").contains(status)) throw new BusinessException(400, "status is invalid");
        int updated = jdbc.update("update `user` set status=? where id=? and deleted_at is null", status, userId);
        if (updated == 0) throw new BusinessException(404, "user not found");
        return userView(userId);
    }

    private Map<String, Object> normalizeAdminRow(Map<String, Object> raw) {
        Map<String, Object> out = new LinkedHashMap<>();
        raw.forEach((key, value) -> out.put(key, value instanceof Timestamp ts ? iso(ts) : value));
        return out;
    }
    private Map<String, Object> findUserByPhone(String phone) {
        try {
            return jdbc.queryForObject("select id,phone,password_hash passwordHash,nickname,avatar_url avatarUrl,timezone,status,created_at createdAt from `user` where phone=? and deleted_at is null", userMapper(), phone);
        } catch (EmptyResultDataAccessException ex) {
            return null;
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

    private RowMapper<Map<String, Object>> scheduleMapper() {
        return (rs, i) -> {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", rs.getLong("id"));
            m.put("userId", rs.getLong("userId"));
            m.put("title", rs.getString("title"));
            m.put("description", rs.getString("description"));
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

    private void createScheduleReminders(long userId, long scheduleId, Map<String, Object> req) {
        Object arr = req.get("remindAts");
        if (arr instanceof List<?> list) {
            for (Object v : list) insertReminder(userId, "schedule", scheduleId, parseTime(String.valueOf(v)));
        } else if (req.get("remindAt") != null) {
            insertReminder(userId, "schedule", scheduleId, parseTime(String.valueOf(req.get("remindAt"))));
        }
    }

    private void insertReminder(long userId, String type, long id, Timestamp remindAt) {
        if (remindAt != null) jdbc.update("insert into reminder (user_id,target_type,target_id,remind_at,status) values (?,?,?,?, 'pending')", userId, type, id, remindAt);
    }

    private void cancelPendingReminders(String type, long id, Long userId) {
        if (userId == null) jdbc.update("update reminder set status='cancelled' where target_type=? and target_id=? and status='pending'", type, id);
        else jdbc.update("update reminder set status='cancelled' where target_type=? and target_id=? and user_id=? and status='pending'", type, id, userId);
    }

    private Integer count(String sql, Object... args) {
        Integer n = jdbc.queryForObject(sql, Integer.class, args);
        return n == null ? 0 : n;
    }

    private static boolean blank(String s) { return s == null || s.isBlank(); }
    private static String text(Map<String, Object> m, String k) { return String.valueOf(m.getOrDefault(k, "")); }
    private static String textOr(Map<String, Object> m, String k, String f) { String v = text(m, k); return blank(v) ? f : v; }
    private static String nullableText(Object v) { if (v == null) return null; String s = String.valueOf(v); return s.isBlank() ? null : s; }
    private static long longValue(Object v) { return ((Number) v).longValue(); } private static long number(Object v) { return v instanceof Number n ? n.longValue() : Long.parseLong(String.valueOf(v)); }
    private static Timestamp parseOptional(Object v) { return v == null ? null : parseTime(String.valueOf(v)); }
    private static Timestamp parseTime(String value) {
        if (value == null || value.isBlank()) return null;
        try { return Timestamp.from(OffsetDateTime.parse(value).toInstant()); } catch (Exception ignored) {}
        try { return Timestamp.valueOf(LocalDateTime.parse(value)); } catch (Exception ignored) {}
        return null;
    }    private static String primaryTime(Map<String, Object> item) {
        String deadline = String.valueOf(item.getOrDefault("deadlineTime", ""));
        return deadline.isBlank() ? String.valueOf(item.getOrDefault("startTime", "")) : deadline;
    }

    private static boolean sameDate(String iso, LocalDate date) {
        return iso != null && iso.startsWith(date.toString());
    }

    private static String iso(Timestamp ts) { return ts == null ? "" : OffsetDateTime.ofInstant(ts.toInstant(), ZoneOffset.UTC).toString(); }
}


