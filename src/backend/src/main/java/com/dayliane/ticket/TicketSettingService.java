package com.dayliane.ticket;

import com.dayliane.admin.AdminService;
import com.dayliane.common.BusinessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Timestamp;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 工单模块功能开关：数据库单行配置，默认关闭。
 *
 * 与注册开关（RegistrationSettingsService / AdminRegistrationSettingsService）同一套写法：
 * 用户端每次访问都读库，不依赖前端菜单或进程内缓存；超级管理员才能切换，切换写入管理操作日志。
 */
@Service
public class TicketSettingService {

    private static final long SETTINGS_ID = 1L;
    /** 前端集中处理的统一业务错误标识（计划 §7.3）。 */
    public static final String DISABLED_MESSAGE = "TICKET_FEATURE_DISABLED";

    private final JdbcTemplate jdbc;
    private final AdminService adminService;

    public TicketSettingService(JdbcTemplate jdbc, AdminService adminService) {
        this.jdbc = jdbc;
        this.adminService = adminService;
    }

    public boolean isEnabled() {
        List<Boolean> rows = jdbc.query("select enabled from ticket_setting where id=?",
                (rs, index) -> rs.getBoolean("enabled"), SETTINGS_ID);
        // 记录缺失时按关闭处理：无法确认的状态不视为开启。
        return !rows.isEmpty() && rows.get(0);
    }

    public void requireEnabled() {
        if (!isEnabled()) throw new BusinessException(503, DISABLED_MESSAGE);
    }

    public Map<String, Object> current() {
        List<Map<String, Object>> rows = jdbc.query(
                "select enabled,version,updated_at from ticket_setting where id=?",
                (rs, index) -> {
                    Map<String, Object> row = new LinkedHashMap<>();
                    row.put("enabled", rs.getBoolean("enabled"));
                    row.put("version", rs.getLong("version"));
                    row.put("updatedAt", rs.getTimestamp("updated_at"));
                    return row;
                }, SETTINGS_ID);
        if (rows.isEmpty()) return view(false, 0L, null);
        Map<String, Object> row = rows.get(0);
        return view((Boolean) row.get("enabled"),
                (Long) row.get("version"),
                (Timestamp) row.get("updatedAt"));
    }

    public long currentVersion() {
        return ((Number) current().getOrDefault("version", 0L)).longValue();
    }

    @Transactional
    public Map<String, Object> update(boolean enabled, long updatedBy) {
        return update(enabled, updatedBy, null, null);
    }

    @Transactional
    public Map<String, Object> update(boolean enabled, long updatedBy, String ipAddress, String userAgent) {
        Map<String, Object> before = current();
        if ("database".equals(sourceOf(before)) && Boolean.TRUE.equals(before.get("enabled")) == enabled) {
            return before;
        }
        int updated = jdbc.update("update ticket_setting set enabled=?,updated_by=?,version=version+1,updated_at=utc_timestamp() where id=?",
                enabled, updatedBy, SETTINGS_ID);
        if (updated == 0) {
            jdbc.update("insert into ticket_setting (id,enabled,updated_by,version) values (?,?,?,1)",
                    SETTINGS_ID, enabled, updatedBy);
        }
        Map<String, Object> after = current();
        adminService.writeAdminOperationLog(updatedBy, "set_ticket_enabled", "ticket_setting", SETTINGS_ID,
                before, after, ipAddress, userAgent);
        return after;
    }

    private String sourceOf(Map<String, Object> view) {
        return view.get("updatedAt") == null || "".equals(view.get("updatedAt")) ? "default" : "database";
    }

    private static Map<String, Object> view(boolean enabled, long version, Timestamp updatedAt) {
        return new java.util.LinkedHashMap<>(Map.of(
                "enabled", enabled,
                "version", version,
                "updatedAt", updatedAt == null ? "" : OffsetDateTime.ofInstant(updatedAt.toInstant(), ZoneOffset.UTC).toString()));
    }
}
