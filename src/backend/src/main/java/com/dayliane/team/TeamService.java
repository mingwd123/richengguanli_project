package com.dayliane.team;

import com.dayliane.common.BusinessException;
import com.dayliane.common.PermissionService;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.sql.PreparedStatement;
import java.sql.Statement;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.*;

@Service
public class TeamService {

    private final JdbcTemplate jdbc;
    private final PermissionService permissionService;

    public TeamService(JdbcTemplate jdbc, PermissionService permissionService) {
        this.jdbc = jdbc;
        this.permissionService = permissionService;
    }

    @Transactional
    public Map<String, Object> createTeam(long userId, String name) {
        if (blank(name)) throw new BusinessException(400, "team name is required");
        String code = inviteCode();
        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbc.update(con -> {
            PreparedStatement ps = con.prepareStatement("insert into team (name,invite_code,invite_code_expire_at,owner_id,status) values (?,?,?,?, 'active')", new String[]{"id"});
            ps.setString(1, name.trim());
            ps.setString(2, code);
            ps.setTimestamp(3, inviteExpireAt());
            ps.setLong(4, userId);
            return ps;
        }, keyHolder);
        long teamId = Objects.requireNonNull(keyHolder.getKey()).longValue();
        jdbc.update("insert into team_member (team_id,user_id,role,status,joined_at) values (?,?, 'owner','active',utc_timestamp())", teamId, userId);
        return teamView(teamId, userId);
    }

    public Map<String, Object> listTeams(long userId, int page, int size) {
        return listTeams(userId, page, size, "created_desc");
    }

    public Map<String, Object> listTeams(long userId, int page, int size, String sort) {
        String order = switch (sort == null ? "created_desc" : sort) {
            case "created_desc" -> "t.created_at desc, t.id desc";
            case "name_asc" -> "t.name asc, t.id asc";
            default -> throw new BusinessException(400, "sort is invalid");
        };
        List<Map<String, Object>> rows = jdbc.query("select t.id from team t join team_member m on m.team_id=t.id where m.user_id=? and m.status='active' and t.deleted_at is null order by " + order, (rs, i) -> teamView(rs.getLong("id"), userId), userId);
        return pageResult(rows, page, size);
    }

    public Map<String, Object> teamDetail(long teamId, long userId) {
        permissionService.requireActiveMember(teamId, userId);
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
        if (permissionService.isActiveMember(teamId, userId)) throw new BusinessException(409, "already joined team");
        if (count("select count(*) from team_member where team_id=? and user_id=?", teamId, userId) > 0) {
            jdbc.update("update team_member set status='active', role='member', joined_at=utc_timestamp(), removed_at=null, removed_by=null where team_id=? and user_id=?", teamId, userId);
        } else {
            jdbc.update("insert into team_member (team_id,user_id,role,status,joined_at) values (?,?, 'member','active',utc_timestamp())", teamId, userId);
        }
        return teamView(teamId, userId);
    }

    public List<Map<String, Object>> activeMembers(long teamId) {
        return jdbc.query("select m.id,m.team_id teamId,m.user_id userId,m.role,m.status,m.joined_at joinedAt,u.nickname,u.phone,u.avatar_url avatarUrl from team_member m join `user` u on u.id=m.user_id where m.team_id=? and m.status='active' order by m.id", (rs, i) -> {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", rs.getLong("id"));
            m.put("teamId", rs.getLong("teamId"));
            m.put("userId", rs.getLong("userId"));
            m.put("role", rs.getString("role"));
            m.put("status", rs.getString("status"));
            m.put("joinedAt", iso(rs.getTimestamp("joinedAt")));
            m.put("nickname", rs.getString("nickname"));
            m.put("avatarUrl", rs.getString("avatarUrl"));
            m.put("phone", rs.getString("phone"));
            return m;
        }, teamId);
    }

    public void changeRole(long teamId, long targetUserId, long operatorId, String newRole) {
        if (!"owner".equals(permissionService.role(teamId, operatorId))) throw new BusinessException(403, "only owner can change role");
        if (!List.of("admin", "member").contains(newRole)) throw new BusinessException(400, "role is invalid");
        if (longValue(requireTeam(teamId).get("ownerId")) == targetUserId) throw new BusinessException(400, "owner role cannot be changed");
        jdbc.update("update team_member set role=? where team_id=? and user_id=? and status='active'", newRole, teamId, targetUserId);
    }

    @Transactional
    public void removeMember(long teamId, long targetUserId, long operatorId) {
        String operatorRole = permissionService.role(teamId, operatorId);
        String targetRole = permissionService.role(teamId, targetUserId);
        if ("owner".equals(targetRole)) throw new BusinessException(403, "owner cannot be removed");
        if (!"owner".equals(operatorRole) && !("admin".equals(operatorRole) && "member".equals(targetRole))) throw new BusinessException(403, "no permission to remove member");
        jdbc.update("update team_member set status='removed', removed_at=utc_timestamp(), removed_by=? where team_id=? and user_id=?", operatorId, teamId, targetUserId);
        jdbc.update("update team_task_assignee set is_active=false where user_id=? and task_id in (select id from team_task where team_id=?) and is_active=true and status <> 'completed'", targetUserId, teamId);
    }

    public Map<String, Object> regenerateInvite(long teamId, long userId) {
        Map<String, Object> team = requireTeam(teamId);
        if (longValue(team.get("ownerId")) != userId) throw new BusinessException(403, "only owner can regenerate invite code");
        jdbc.update("update team set invite_code=?, invite_code_expire_at=? where id=?", inviteCode(), inviteExpireAt(), teamId);
        return teamView(teamId, userId);
    }

    // ---- private helpers ----

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
        team.put("role", permissionService.role(teamId, userId));
        team.put("memberCount", count("select count(*) from team_member where team_id=? and status='active'", teamId));
        team.put("activeTaskCount", count("select count(*) from team_task where team_id=? and status='active' and deleted_at is null", teamId));
        return team;
    }

    private Timestamp inviteExpireAt() {
        return Timestamp.from(Instant.now().plus(15, ChronoUnit.DAYS));
    }

    private String inviteCode() {
        return UUID.randomUUID().toString().replace("-", "").substring(0, 6).toUpperCase();
    }

    private Map<String, Object> pageResult(List<Map<String, Object>> rows, int page, int size) {
        int p = Math.max(1, page);
        int s = Math.min(100, Math.max(1, size));
        int from = Math.min(rows.size(), (p - 1) * s);
        int to = Math.min(rows.size(), from + s);
        return Map.of("list", rows.subList(from, to), "total", rows.size(), "page", p, "size", s);
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
