package com.dayliane.admin;

import com.dayliane.common.BusinessException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.JdbcUtils;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.Date;
import java.sql.Timestamp;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

@Service
@Transactional(readOnly = true)
public class AdminAnalyticsService {
    private final JdbcTemplate jdbc;
    @Value("${dayliane.features.fatigue.enabled:true}") private boolean fatigueEnabled;
    @Value("${dayliane.features.fatigue.surveys-enabled:true}") private boolean surveysEnabled;
    @Value("${dayliane.features.fatigue.allowed-user-ids:}") private String fatigueAllowedUsers;

    public AdminAnalyticsService(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    public Map<String, Object> view(String group, String metric, AnalyticsRange range, int page, int size) {
        List<Map<String, Object>> items = switch (group + "/" + metric) {
            case "fatigue/distribution" -> fatigueDistribution(range);
            case "fatigue/completion" -> fatigueCompletion(range);
            case "ops/growth" -> growth(range);
            case "ops/retention" -> retention(range);
            case "ops/status-distribution" -> userStatuses(range);
            case "collab/funnel" -> funnel(range);
            case "collab/completion-trend" -> taskCompletion(range);
            case "collab/team-distribution" -> teamDistribution(range);
            case "ai/cost-trend" -> aiCost(range);
            case "ai/feature-distribution" -> query("select feature_type name,count(*) `value`,"
                    + "sum(case when status='failed' then 1 else 0 end) failures from ai_usage_log "
                    + "where created_at>=? and created_at<? group by feature_type order by `value` desc", range.start(), range.end());
            case "ai/key-health" -> keyHealth(range);
            case "fatigue/high-load-users" -> null;
            default -> throw new BusinessException(404, "data view not found");
        };
        if (items == null) return highLoad(range, page, size);
        Map<String, Object> result = result(range, items);
        if ("fatigue".equals(group)) {
            result.put("timezone", "user-local-date");
            result.put("basis", "completion: personal completed-load user-days, current enabled settings; skipped days included; "
                    + "high load: persisted personal summary / current capacity75, excludes team load");
        }
        if ("ops".equals(group)) result.put("trackingStartedAt", trackingStartedAt().toInstant().toString());
        if ("ai".equals(group)) result.put("currency", "CNY");
        return result;
    }

    private List<Map<String, Object>> fatigueDistribution(AnalyticsRange range) {
        List<Map<String, Object>> rows = query("select case when score<20 then 0 when score<40 then 1 "
                + "when score<60 then 2 when score<80 then 3 else 4 end bucket,count(*) total "
                + "from fatigue_survey where local_date between ? and ? group by bucket", range.from(), range.to());
        long[] counts = new long[5];
        rows.forEach(row -> counts[((Number) row.get("bucket")).intValue()] = number(row.get("total")));
        String[] labels = {"0-19", "20-39", "40-59", "60-79", "80-100"};
        List<Map<String, Object>> items = new ArrayList<>();
        for (int i = 0; i < labels.length; i++) items.add(Map.of("name", labels[i], "value", counts[i]));
        return items;
    }

    String fatigueScope() {
        if (!fatigueEnabled) return " and 1=0 ";
        if (fatigueAllowedUsers == null || fatigueAllowedUsers.isBlank()) return "";
        List<String> ids = new ArrayList<>();
        for (String id : fatigueAllowedUsers.split(",")) {
            try { ids.add(Long.toString(Long.parseLong(id.trim()))); }
            catch (NumberFormatException ignored) { }
        }
        return ids.isEmpty() ? " and 1=0 " : " and p.user_id in (" + String.join(",", ids) + ") ";
    }

    String eligibleDays() {
        return "select d.user_id,d.local_date from fatigue_daily_summary d "
                + "join user_fatigue_profile p on p.user_id=d.user_id join `user` u on u.id=d.user_id "
                + "where d.local_date between ? and ? and d.completed_load>0 and p.fatigue_tracking_enabled=true "
                + "and p.survey_enabled=true and u.status='active' and u.deleted_at is null " + fatigueScope()
                + (surveysEnabled ? "" : " and 1=0 ")
                + " union select user_id,local_date from fatigue_survey where local_date between ? and ?";
    }

    private List<Map<String, Object>> fatigueCompletion(AnalyticsRange range) {
        String eligible = eligibleDays();
        List<Map<String, Object>> rows = query("select e.local_date date,count(*) expected,"
                + "sum(case when s.id is not null then 1 else 0 end) completed,"
                + "sum(case when k.user_id is not null and s.id is null then 1 else 0 end) skipped "
                + "from (" + eligible + ") e left join fatigue_survey s on s.user_id=e.user_id and s.local_date=e.local_date "
                + "left join fatigue_survey_skip k on k.user_id=e.user_id and k.local_date=e.local_date "
                + "group by e.local_date order by e.local_date", range.from(), range.to(), range.from(), range.to());
        List<Map<String, Object>> items = daily(range, rows, "expected", "completed", "skipped");
        items.forEach(row -> row.put("rate", ratio(number(row.get("completed")), number(row.get("expected")))));
        return items;
    }

    private Map<String, Object> highLoad(AnalyticsRange range, int page, int size) {
        validatePage(page, size);
        String from = " from fatigue_daily_summary d join user_fatigue_profile p on p.user_id=d.user_id "
                + "join `user` u on u.id=d.user_id where d.local_date between ? and ? "
                + "and p.fatigue_tracking_enabled=true and p.capacity_75>0 and u.deleted_at is null and u.status='active' "
                + "and greatest(d.planned_load,d.completed_load)>=p.capacity_75*0.8 " + fatigueScope();
        long total = jdbc.queryForObject("select count(*)" + from, Long.class, range.from(), range.to());
        List<Map<String, Object>> items = query("select d.user_id,u.nickname,d.local_date date,"
                + "d.planned_load,d.completed_load,p.capacity_75 capacity75,"
                + "round(greatest(d.planned_load,d.completed_load)*100/p.capacity_75,1) load_percent,"
                + "d.predicted_score,d.updated_at snapshot_at,"
                + "case when greatest(d.planned_load,d.completed_load)>=p.capacity_75 then 'high' else 'medium' end risk_level"
                + from + " order by load_percent desc,d.local_date desc,d.user_id limit ? offset ?",
                range.from(), range.to(), size, (page - 1) * size);
        Map<String, Object> result = result(range, items);
        result.put("total", total);
        result.put("page", page);
        result.put("size", size);
        result.put("timezone", "user-local-date");
        return result;
    }

    private List<Map<String, Object>> growth(AnalyticsRange range) {
        List<Map<String, Object>> items = daily(range, query("select date(created_at) date,count(*) new_users "
                + "from `user` where created_at>=? and created_at<? group by date(created_at)",
                range.start(), range.end()), "newUsers");
        long total = jdbc.queryForObject("select count(*) from `user` where created_at<?", Long.class, range.start());
        for (Map<String, Object> item : items) {
            total += number(item.get("newUsers"));
            item.put("totalUsers", total);
        }
        return items;
    }

    private List<Map<String, Object>> retention(AnalyticsRange range) {
        Map<LocalDate, Set<Long>> activity = new HashMap<>();
        jdbc.query("select user_id,activity_date from user_activity_daily where activity_date between ? and ?",
                (org.springframework.jdbc.core.RowCallbackHandler) rs -> activity
                        .computeIfAbsent(rs.getDate(2).toLocalDate(), ignored -> new HashSet<>()).add(rs.getLong(1)),
                range.from().minusDays(6), range.to().plusDays(7));
        Map<LocalDate, Set<Long>> cohorts = new HashMap<>();
        jdbc.query("select id,created_at from `user` where created_at>=? and created_at<?",
                (org.springframework.jdbc.core.RowCallbackHandler) rs -> cohorts
                        .computeIfAbsent(rs.getTimestamp(2).toLocalDateTime().toLocalDate(), ignored -> new HashSet<>())
                        .add(rs.getLong(1)), range.start(), range.end());
        LocalDate today = LocalDate.now(ZoneOffset.UTC);
        LocalDate firstFullDay = trackingStartedAt().toLocalDateTime().toLocalDate().plusDays(1);
        List<Map<String, Object>> items = new ArrayList<>();
        for (LocalDate day = range.from(); !day.isAfter(range.to()); day = day.plusDays(1)) {
            Set<Long> cohort = cohorts.getOrDefault(day, Set.of());
            Set<Long> week = new HashSet<>();
            for (int i = 0; i < 7; i++) week.addAll(activity.getOrDefault(day.minusDays(i), Set.of()));
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("date", day.toString());
            item.put("dau", day.isBefore(firstFullDay) ? null : activity.getOrDefault(day, Set.of()).size());
            item.put("wau", day.minusDays(6).isBefore(firstFullDay) ? null : week.size());
            item.put("cohort", cohort.size());
            for (int offset : List.of(1, 7)) {
                LocalDate target = day.plusDays(offset);
                Set<Long> returned = new HashSet<>(cohort);
                returned.retainAll(activity.getOrDefault(target, Set.of()));
                item.put(offset == 1 ? "nextDayRate" : "day7Rate",
                        target.isBefore(firstFullDay) || !target.isBefore(today) ? null : ratio(returned.size(), cohort.size()));
            }
            items.add(item);
        }
        return items;
    }

    private List<Map<String, Object>> userStatuses(AnalyticsRange range) {
        return query("select case when status='disabled' then 'disabled' when email_verified_at is null then 'unverified' "
                + "else 'active' end name,count(*) `value` from `user` where deleted_at is null "
                + "and created_at>=? and created_at<? group by name order by name", range.start(), range.end());
    }

    private List<Map<String, Object>> funnel(AnalyticsRange range) {
        Map<String, Object> row = jdbc.queryForMap("select count(*) created,"
                + "coalesce(sum(case when t.status='completed' or exists (select 1 from team_task_assignee a where a.task_id=t.id "
                + "and (a.accepted_at is not null or a.completed_at is not null)) then 1 else 0 end),0) accepted,"
                + "coalesce(sum(case when t.status='completed' then 1 else 0 end),0) completed "
                + "from team_task t where t.deleted_at is null and t.created_at>=? and t.created_at<?", range.start(), range.end());
        return List.of(Map.of("name", "created", "value", row.get("created")),
                Map.of("name", "accepted", "value", row.get("accepted")),
                Map.of("name", "completed", "value", row.get("completed")));
    }

    private List<Map<String, Object>> taskCompletion(AnalyticsRange range) {
        String tasks = settledTasks();
        List<Map<String, Object>> items = daily(range, query("select date(settled_at) date,"
                + "sum(case when status='completed' and (deadline_time is null or settled_at<=deadline_time) then 1 else 0 end) on_time,"
                + "sum(case when status='completed' and deadline_time is not null and settled_at>deadline_time then 1 else 0 end) overdue,"
                + "sum(case when status='cancelled' then 1 else 0 end) cancelled "
                + "from (" + tasks + ") t where settled_at>=? and settled_at<? group by date(settled_at)",
                range.start(), range.end()), "onTime", "overdue", "cancelled");
        items.forEach(item -> {
            long done = number(item.get("onTime")) + number(item.get("overdue"));
            item.put("rate", ratio(done, done + number(item.get("cancelled"))));
            item.put("onTimeRate", ratio(number(item.get("onTime")), done));
        });
        return items;
    }

    String settledTasks() {
        return "select t.id task_id,t.team_id,t.title,t.status,t.deadline_time,case when t.status='completed' then "
                + "(select max(a.completed_at) from team_task_assignee a where a.task_id=t.id and a.is_active=true) "
                + "else (select max(e.created_at) from team_task_event e where e.task_id=t.id and e.event_type='cancelled') end settled_at "
                + "from team_task t where t.deleted_at is null and t.status in ('completed','cancelled')";
    }

    private List<Map<String, Object>> teamDistribution(AnalyticsRange range) {
        return query("select case when members=0 then '0' when members<=5 then '1-5' when members<=10 then '6-10' "
                + "when members<=20 then '11-20' else '21+' end name,count(*) `value` from "
                + "(select t.id,count(m.id) members from team t left join team_member m on m.team_id=t.id and m.status='active' "
                + "where t.deleted_at is null and t.created_at>=? and t.created_at<? group by t.id) sizes group by name",
                range.start(), range.end());
    }

    private List<Map<String, Object>> aiCost(AnalyticsRange range) {
        List<Map<String, Object>> items = daily(range, query("select date(created_at) date,count(*) calls,"
                + "sum(case when status='failed' then 1 else 0 end) failures,"
                + "sum(coalesce(input_tokens,0)+coalesce(output_tokens,0)) tokens,"
                + "sum(estimated_cost) cost,count(estimated_cost) priced_calls,count(input_tokens) metered_calls "
                + "from ai_usage_log where created_at>=? and created_at<? group by date(created_at)",
                range.start(), range.end()), "calls", "failures", "tokens", "cost", "pricedCalls", "meteredCalls");
        items.forEach(item -> {
            if (number(item.get("calls")) > 0 && number(item.get("pricedCalls")) == 0) item.put("cost", null);
            if (number(item.get("calls")) > 0 && number(item.get("meteredCalls")) == 0) item.put("tokens", null);
        });
        return items;
    }

    private List<Map<String, Object>> keyHealth(AnalyticsRange range) {
        List<Map<String, Object>> items = query("select c.key_id,"
                + "coalesce(k.name,case when c.key_id is null then '环境变量 Key' else '已删除 Key' end) name,"
                + "count(*) calls,sum(case when c.success=false then 1 else 0 end) failures,"
                + "sum(case when c.switched=true then 1 else 0 end) switches "
                + "from ai_key_call_log c left join ai_api_key k on k.id=c.key_id where c.created_at>=? and c.created_at<? "
                + "group by c.key_id,k.name order by calls desc", range.start(), range.end());
        items.forEach(item -> item.put("failureRate", ratio(number(item.get("failures")), number(item.get("calls")))));
        return items;
    }

    public Map<String, Object> anomalies(AnalyticsRange range, int page, int size, Long userId) {
        validatePage(page, size);
        String from = " from security_event e left join `user` u on u.id=e.user_id "
                + "where e.created_at>=? and e.created_at<? and e.event_type in ('login_failure','login_success') "
                + "and e.risk_level<>'low'" + (userId == null ? "" : " and e.user_id=?");
        List<Object> params = new ArrayList<>(List.of(range.start(), range.end()));
        if (userId != null) params.add(userId);
        long total = jdbc.queryForObject("select count(*)" + from, Long.class, params.toArray());
        params.add(size);
        params.add((page - 1) * size);
        Map<String, Object> result = result(range, query("select e.id,e.user_id,u.nickname,u.status user_status,"
                + "e.ip_address,e.risk_level,e.reason,e.created_at"
                + ",e.review_status,e.review_note,e.reviewed_by,e.reviewed_at,e.revision"
                + from + " order by e.created_at desc,e.id desc limit ? offset ?", params.toArray()));
        result.put("total", total);
        result.put("page", page);
        result.put("size", size);
        result.put("trackingStartedAt", trackingStartedAt().toInstant().toString());
        return result;
    }

    public Map<String, Object> registrations(AnalyticsRange range) {
        return registrations(range, 1, 20);
    }

    public Map<String, Object> registrations(AnalyticsRange range, int page, int size) {
        validatePage(page, size);
        String grouped = "select date(created_at) date,ip_segment,count(*) registrations,"
                + "case when count(*)>=10 then 'high' when count(*)>=5 then 'medium' else 'low' end risk_level "
                + "from security_event where event_type='registration' and created_at>=? and created_at<? "
                + "group by date(created_at),ip_segment";
        List<Map<String, Object>> items = query(grouped + " order by registrations desc,date desc,ip_segment limit ? offset ?",
                range.start(), range.end(), size, (page - 1) * size);
        Map<String, Object> result = result(range, items);
        result.put("trackingStartedAt", trackingStartedAt().toInstant().toString());
        result.put("total", jdbc.queryForObject("select count(*) from (" + grouped + ") grouped", Long.class, range.start(), range.end()));
        result.put("summary", query("select sum(registrations) registrations,sum(case when registrations>=10 then 1 else 0 end) high_risk_sources "
                + "from (" + grouped + ") grouped", range.start(), range.end()).get(0));
        result.put("page", page);
        result.put("size", size);
        return result;
    }

    public Map<String, Object> quotaAlerts(AnalyticsRange range, int page, int size) {
        validatePage(page, size);
        Map<String, Object> result = result(range, query("select id,user_id,scope_type,subject_id,"
                + "daily_limit,created_at,alert_kind,used_calls,usage_date from ai_quota_alert where created_at>=? and created_at<? "
                + "order by id desc limit ? offset ?", range.start(), range.end(), size, (page - 1) * size));
        result.put("total", jdbc.queryForObject("select count(*) from ai_quota_alert where created_at>=? and created_at<?",
                Long.class, range.start(), range.end()));
        return result;
    }

    private Timestamp trackingStartedAt() {
        return jdbc.queryForObject("select started_at from admin_analytics_state where id=1", Timestamp.class);
    }

    List<Map<String, Object>> query(String sql, Object... args) {
        return jdbc.query(sql, (rs, index) -> {
            Map<String, Object> row = new LinkedHashMap<>();
            for (int i = 1; i <= rs.getMetaData().getColumnCount(); i++) {
                Object value = rs.getObject(i);
                if (value instanceof Timestamp timestamp) value = timestamp.toLocalDateTime().atOffset(ZoneOffset.UTC).toString();
                if (value instanceof Date date) value = date.toString();
                if (value instanceof byte[] bytes) value = new String(bytes, java.nio.charset.StandardCharsets.UTF_8);
                row.put(JdbcUtils.convertUnderscoreNameToPropertyName(rs.getMetaData().getColumnLabel(i)), value);
            }
            return row;
        }, args);
    }

    private static List<Map<String, Object>> daily(AnalyticsRange range, List<Map<String, Object>> rows, String... fields) {
        Map<String, Map<String, Object>> byDay = new HashMap<>();
        rows.forEach(row -> byDay.put(String.valueOf(row.get("date")), row));
        List<Map<String, Object>> items = new ArrayList<>();
        for (LocalDate date = range.from(); !date.isAfter(range.to()); date = date.plusDays(1)) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("date", date.toString());
            for (String field : fields) row.put(field, 0);
            row.putAll(byDay.getOrDefault(date.toString(), Map.of()));
            items.add(row);
        }
        return items;
    }

    private static Map<String, Object> result(AnalyticsRange range, List<Map<String, Object>> items) {
        Map<String, Object> result = new LinkedHashMap<>(range.metadata());
        result.put("items", items);
        return result;
    }

    private static long number(Object value) { return value instanceof Number n ? n.longValue() : 0; }
    private static BigDecimal ratio(long numerator, long denominator) {
        return denominator == 0 ? null : BigDecimal.valueOf(numerator * 100).divide(BigDecimal.valueOf(denominator), 1, RoundingMode.HALF_UP);
    }
    private static void validatePage(int page, int size) {
        if (page < 1 || page > 1000000 || size < 1 || size > 100) throw new BusinessException(400, "invalid pagination");
    }
}
