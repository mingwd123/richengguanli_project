package com.dayliane.ai;

import com.dayliane.admin.AdminService;
import com.dayliane.common.BusinessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Service
public class AiQuotaService {
    private final JdbcTemplate jdbc;
    private final AdminService adminService;

    public AiQuotaService(JdbcTemplate jdbc, AdminService adminService) {
        this.jdbc = jdbc;
        this.adminService = adminService;
    }

    @Transactional(readOnly = true)
    public Map<String, Object> settings() {
        Map<String, Object> result = new LinkedHashMap<>(jdbc.queryForObject(
                "select enabled,global_daily_limit,user_daily_limit,team_daily_limit,revision,warning_percent from ai_quota_setting where id=1",
                (rs, index) -> Map.of("enabled", rs.getBoolean("enabled"),
                        "globalDailyLimit", rs.getLong("global_daily_limit"), "userDailyLimit", rs.getLong("user_daily_limit"),
                        "teamDailyLimit", rs.getLong("team_daily_limit"), "revision", rs.getLong("revision"),
                        "warningPercent", rs.getInt("warning_percent"))));
        result.put("prices", jdbc.query("select model_name,input_per_million,output_per_million from ai_model_price order by model_name",
                (rs, index) -> Map.of("modelName", rs.getString("model_name"), "inputPerMillion", rs.getBigDecimal("input_per_million"),
                        "outputPerMillion", rs.getBigDecimal("output_per_million"))));
        result.put("todayCalls", calls("global", 0, LocalDate.now(ZoneOffset.UTC)));
        result.put("overrides", jdbc.query("select scope_type,subject_id,daily_limit,enabled from ai_quota_override order by scope_type,subject_id",
                (rs, index) -> Map.of("scopeType", rs.getString(1), "subjectId", rs.getLong(2),
                        "dailyLimit", rs.getLong(3), "enabled", rs.getBoolean(4))));
        result.put("usage", usage(1, 20));
        result.put("timezone", "UTC");
        result.put("currency", "CNY");
        return result;
    }

    public Map<String, Object> usage(int page, int size) {
        if (page < 1 || page > 1000000 || size < 1 || size > 100) throw new BusinessException(400, "invalid pagination");
        LocalDate today = LocalDate.now(ZoneOffset.UTC);
        return Map.of("page", page, "size", size,
                "total", jdbc.queryForObject("select count(*) from ai_quota_daily where usage_date=?", Long.class, today),
                "items", jdbc.query("select d.scope_type,d.subject_id,d.calls,case when o.enabled=true then o.daily_limit "
                        + "when d.scope_type='global' then s.global_daily_limit when d.scope_type='team' then s.team_daily_limit "
                        + "else s.user_daily_limit end daily_limit from ai_quota_daily d cross join ai_quota_setting s "
                        + "left join ai_quota_override o on o.scope_type=d.scope_type and o.subject_id=d.subject_id "
                        + "where d.usage_date=? order by d.calls desc,d.scope_type,d.subject_id limit ? offset ?",
                        (rs, i) -> Map.of("scopeType", rs.getString(1), "subjectId", rs.getLong(2),
                                "calls", rs.getLong(3), "dailyLimit", rs.getLong(4)), today, size, (page - 1) * size));
    }

    @Transactional
    public Map<String, Object> update(long adminId, Map<String, Object> request, String ip, String userAgent) {
        adminService.requireSuperAdmin(adminId);
        Map<String, Object> locked = jdbc.queryForMap("select revision from ai_quota_setting where id=1 for update");
        long revision = integer(request.get("revision"), 0, Long.MAX_VALUE, "revision");
        if (revision != ((Number) locked.get("revision")).longValue()) {
            throw new BusinessException(409, "quota settings changed; refresh and retry");
        }
        if (!(request.get("enabled") instanceof Boolean)) throw new BusinessException(400, "enabled must be boolean");
        long global = integer(request.get("globalDailyLimit"), 1, 100000000, "globalDailyLimit");
        long user = integer(request.get("userDailyLimit"), 1, 100000000, "userDailyLimit");
        long team = integer(request.get("teamDailyLimit"), 1, 100000000, "teamDailyLimit");
        long warning = integer(request.getOrDefault("warningPercent", 80), 1, 100, "warningPercent");
        List<Map<String, Object>> overrides = new ArrayList<>();
        if (!(request.getOrDefault("overrides", List.of()) instanceof List<?> rawOverrides) || rawOverrides.size() > 200) {
            throw new BusinessException(400, "at most 200 quota overrides are allowed");
        }
        for (Object raw : rawOverrides) {
            if (!(raw instanceof Map<?, ?> item)) throw new BusinessException(400, "invalid quota override");
            String scope = String.valueOf(item.get("scopeType"));
            if (!List.of("user", "team").contains(scope)) throw new BusinessException(400, "invalid override scope");
            long subject = integer(item.get("subjectId"), 1, Long.MAX_VALUE, "subjectId");
            long limit = integer(item.get("dailyLimit"), 1, 100000000, "dailyLimit");
            if (!(item.get("enabled") instanceof Boolean)) throw new BusinessException(400, "override enabled must be boolean");
            if (overrides.stream().anyMatch(o -> scope.equals(o.get("scopeType")) && Long.valueOf(subject).equals(o.get("subjectId"))))
                throw new BusinessException(400, "duplicate override");
            String table = "user".equals(scope) ? "`user`" : "team";
            if (jdbc.queryForObject("select count(*) from " + table + " where id=? and deleted_at is null", Long.class, subject) == 0)
                throw new BusinessException(404, "quota subject not found");
            overrides.add(Map.of("scopeType", scope, "subjectId", subject, "dailyLimit", limit, "enabled", item.get("enabled")));
        }
        if (!(request.get("prices") instanceof List<?> prices) || prices.size() > 50) {
            throw new BusinessException(400, "prices must contain at most 50 models");
        }
        List<Map<String, Object>> checked = new ArrayList<>();
        for (Object item : prices) {
            if (!(item instanceof Map<?, ?> price)) throw new BusinessException(400, "invalid model price");
            String model = String.valueOf(price.getOrDefault("modelName", null)).trim();
            if (model.isBlank() || model.equals("null") || model.length() > 100
                    || checked.stream().anyMatch(p -> model.equalsIgnoreCase(String.valueOf(p.get("modelName"))))) {
                throw new BusinessException(400, "model names must be unique and nonempty");
            }
            checked.add(Map.of("modelName", model, "inputPerMillion", price(price.get("inputPerMillion")),
                    "outputPerMillion", price(price.get("outputPerMillion"))));
        }
        Map<String, Object> before = settings();
        jdbc.update("update ai_quota_setting set enabled=?,global_daily_limit=?,user_daily_limit=?,"
                + "team_daily_limit=?,warning_percent=?,revision=revision+1,updated_by=?,updated_at=utc_timestamp() where id=1",
                request.get("enabled"), global, user, team, warning, adminId);
        jdbc.update("delete from ai_quota_override");
        for (Map<String, Object> item : overrides) jdbc.update(
                "insert into ai_quota_override (scope_type,subject_id,daily_limit,enabled) values (?,?,?,?)",
                item.get("scopeType"), item.get("subjectId"), item.get("dailyLimit"), item.get("enabled"));
        jdbc.update("delete from ai_model_price");
        for (Map<String, Object> price : checked) jdbc.update(
                "insert into ai_model_price (model_name,input_per_million,output_per_million) values (?,?,?)",
                price.get("modelName"), price.get("inputPerMillion"), price.get("outputPerMillion"));
        Map<String, Object> after = settings();
        adminService.writeAdminOperationLog(adminId, "update_ai_quota", "ai_quota", 1L, before, after, ip, userAgent);
        return after;
    }

    // A short database lock serializes admission across application instances, not the remote AI call.
    // Business rejections commit their alert but never increment any of the counters.
    @Transactional(noRollbackFor = BusinessException.class)
    public void reserve(long userId) {
        reserve(userId, null);
    }

    @Transactional(noRollbackFor = BusinessException.class)
    public void reserve(long userId, Long teamId) {
        Map<String, Object> settings = jdbc.queryForMap("select * from ai_quota_setting where id=1 for update");
        LocalDate day = LocalDate.now(ZoneOffset.UTC);
        List<Scope> scopes = new ArrayList<>();
        scopes.add(new Scope("global", 0, ((Number) settings.get("global_daily_limit")).longValue()));
        scopes.add(new Scope("user", userId, ((Number) settings.get("user_daily_limit")).longValue()));
        if (teamId != null) {
            if (jdbc.queryForObject("select count(*) from team_member m join team t on t.id=m.team_id "
                    + "where m.user_id=? and m.team_id=? and m.status='active' and t.deleted_at is null and t.status='active'",
                    Long.class, userId, teamId) == 0) throw new BusinessException(403, "active team membership is required");
            scopes.add(new Scope("team", teamId, ((Number) settings.get("team_daily_limit")).longValue()));
        }
        scopes.replaceAll(scope -> {
            List<Long> override = jdbc.queryForList("select daily_limit from ai_quota_override where scope_type=? and subject_id=? and enabled=true",
                    Long.class, scope.type(), scope.id());
            return override.isEmpty() ? scope : new Scope(scope.type(), scope.id(), override.get(0));
        });
        if (Boolean.TRUE.equals(settings.get("enabled"))) {
            for (Scope scope : scopes) {
                if (calls(scope.type(), scope.id(), day) >= scope.limit()) {
                    alert(userId, scope, day, "exceeded", calls(scope.type(), scope.id(), day));
                    throw new BusinessException(429, "AI_QUOTA_EXCEEDED: " + scope.type() + " daily call limit reached (UTC)");
                }
            }
        }
        for (Scope scope : scopes) {
            if (jdbc.update("update ai_quota_daily set calls=calls+1 where scope_type=? and subject_id=? and usage_date=?",
                    scope.type(), scope.id(), day) == 0) {
                jdbc.update("insert into ai_quota_daily (scope_type,subject_id,usage_date,calls) values (?,?,?,1)",
                        scope.type(), scope.id(), day);
            }
            long used = calls(scope.type(), scope.id(), day);
            if (Boolean.TRUE.equals(settings.get("enabled"))
                    && used * 100 >= scope.limit() * ((Number) settings.get("warning_percent")).longValue()) {
                alert(userId, scope, day, "near_limit", used);
            }
        }
    }

    private void alert(long userId, Scope scope, LocalDate day, String kind, long used) {
        if (jdbc.queryForObject("select count(*) from ai_quota_alert where scope_type=? and subject_id=? and usage_date=? and alert_kind=?",
                Long.class, scope.type(), scope.id(), day, kind) > 0) return;
        jdbc.update("insert into ai_quota_alert (user_id,scope_type,subject_id,daily_limit,alert_kind,used_calls,usage_date) values (?,?,?,?,?,?,?)",
                userId, scope.type(), scope.id(), scope.limit(), kind, used, day);
    }

    public BigDecimal estimate(String model, Long input, Long output) {
        if (input == null || output == null) return null;
        List<Map<String, Object>> rows = jdbc.queryForList(
                "select input_per_million,output_per_million from ai_model_price where model_name=?", model);
        if (rows.isEmpty()) return null;
        return ((BigDecimal) rows.get(0).get("input_per_million")).multiply(BigDecimal.valueOf(input))
                .add(((BigDecimal) rows.get(0).get("output_per_million")).multiply(BigDecimal.valueOf(output)))
                .divide(BigDecimal.valueOf(1000000), 8, RoundingMode.HALF_UP);
    }

    private long calls(String type, long id, LocalDate day) {
        return jdbc.queryForObject("select coalesce(sum(calls),0) from ai_quota_daily "
                + "where scope_type=? and subject_id=? and usage_date=?", Long.class, type, id, day);
    }

    private static long integer(Object value, long min, long max, String field) {
        try {
            if (!(value instanceof Number)) throw new IllegalArgumentException();
            long number = new BigDecimal(value.toString()).longValueExact();
            if (number < min || number > max) throw new IllegalArgumentException();
            return number;
        } catch (RuntimeException ex) { throw new BusinessException(400, field + " is invalid"); }
    }

    private static BigDecimal price(Object value) {
        try {
            if (!(value instanceof Number)) throw new IllegalArgumentException();
            BigDecimal price = new BigDecimal(value.toString()).setScale(6, RoundingMode.UNNECESSARY);
            if (price.signum() < 0 || price.compareTo(BigDecimal.valueOf(1000000)) > 0) throw new IllegalArgumentException();
            return price;
        } catch (RuntimeException ex) { throw new BusinessException(400, "model price is invalid"); }
    }

    private record Scope(String type, long id, long limit) {}
}
