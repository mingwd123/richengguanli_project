package com.dayliane.admin;

import com.dayliane.common.BusinessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Service
@Transactional(readOnly = true)
public class AdminInspectionService {
    private static final int EXPORT_LIMIT = 20000;
    private final JdbcTemplate jdbc;
    private final AdminAnalyticsService analytics;
    private final AdminService admin;

    public AdminInspectionService(JdbcTemplate jdbc, AdminAnalyticsService analytics, AdminService admin) {
        this.jdbc = jdbc;
        this.analytics = analytics;
        this.admin = admin;
    }

    public Map<String, Object> workspace(long userId, String section, int page, int size) {
        admin.adminUserDetail(userId);
        String sql = switch (section) {
            case "teams" -> "select t.id team_id,t.name,m.role,m.status,m.joined_at from team_member m "
                    + "join team t on t.id=m.team_id where m.user_id=? and t.deleted_at is null";
            case "fatigue" -> "select d.local_date,d.timezone_snapshot,d.planned_load,d.completed_load,d.predicted_score,"
                    + "s.score survey_score,d.updated_at from fatigue_daily_summary d left join fatigue_survey s "
                    + "on s.user_id=d.user_id and s.local_date=d.local_date where d.user_id=?";
            case "ai" -> "select id usage_log_id,feature_type,status,model_name,input_tokens,output_tokens,estimated_cost,"
                    + "team_id,failure_kind,created_at from ai_usage_log where user_id=?";
            case "risks" -> "select id,event_type,risk_level,reason,ip_address,review_status,review_note,reviewed_by,reviewed_at,"
                    + "created_at from security_event where user_id=? and risk_level<>'low'";
            case "audit" -> "select l.id,l.admin_id,a.username,l.action,l.target_type,l.target_id,l.after_data,l.created_at "
                    + "from admin_operation_log l left join admin_user a on a.id=l.admin_id where "
                    + "(l.target_type='user' and l.target_id=?) or (l.target_type='security_event' and exists "
                    + "(select 1 from security_event e where e.id=l.target_id and e.user_id=?))";
            default -> throw new BusinessException(404, "workspace section not found");
        };
        String order = switch (section) {
            case "teams" -> "team_id desc";
            case "fatigue" -> "local_date desc";
            default -> "created_at desc," + ("ai".equals(section) ? "usage_log_id" : "id") + " desc";
        };
        return paged(sql, "audit".equals(section) ? List.of(userId, userId) : List.of(userId), order, page, size);
    }

    @Transactional
    public Map<String, Object> review(long adminId, long eventId, Map<String, Object> body, String ip, String agent) {
        String status = String.valueOf(body.get("status"));
        String note = body.get("note") instanceof String text ? text.trim() : "";
        if (!List.of("pending", "confirmed", "false_positive", "resolved").contains(status)
                || note.isBlank() || note.length() > 1000 || !(body.get("revision") instanceof Number revision)) {
            throw new BusinessException(400, "valid review status, revision and a note (1-1000 characters) are required");
        }
        List<Map<String, Object>> rows = analytics.query("select id,user_id,review_status,review_note,reviewed_by,reviewed_at,revision "
                + "from security_event where id=? and risk_level<>'low' for update", eventId);
        if (rows.isEmpty()) throw new BusinessException(404, "risk event not found");
        Map<String, Object> before = rows.get(0);
        long expectedRevision;
        try { expectedRevision = new java.math.BigDecimal(revision.toString()).longValueExact(); }
        catch (RuntimeException ex) { throw new BusinessException(400, "revision must be an integer"); }
        if (((Number) before.get("revision")).longValue() != expectedRevision)
            throw new BusinessException(409, "risk review changed; refresh and retry");
        jdbc.update("update security_event set review_status=?,review_note=?,reviewed_by=?,reviewed_at=utc_timestamp(),revision=revision+1 where id=?",
                status, note, adminId, eventId);
        Map<String, Object> after = analytics.query("select id,user_id,review_status,review_note,reviewed_by,reviewed_at,revision "
                + "from security_event where id=?", eventId).get(0);
        admin.writeAdminOperationLog(adminId, "review_risk", "security_event", eventId, before, after, ip, agent);
        return after;
    }

    public Map<String, Object> aiLog(long id) {
        List<Map<String, Object>> rows = analytics.query("select id,user_id,feature_type,status,input_text,output_text,error_message,"
                + "model_name,input_tokens,output_tokens,estimated_cost,key_id,switch_count,team_id,failure_kind,latency_ms,created_at "
                + "from ai_usage_log where id=?", id);
        if (rows.isEmpty()) throw new BusinessException(404, "AI log not found");
        return rows.get(0);
    }

    public Map<String, Object> details(String group, String metric, AnalyticsRange range, String selector, String series, int page, int size) {
        selector = selector == null ? "" : selector;
        series = series == null ? "" : series;
        List<Object> args = new ArrayList<>();
        String sql;
        String order = "1 desc";
        switch (group + "/" + metric) {
            case "fatigue/distribution" -> {
                int bucket = List.of("0-19", "20-39", "40-59", "60-79", "80-100").indexOf(selector);
                if (bucket < 0) throw new BusinessException(400, "invalid fatigue bucket");
                sql = "select s.user_id,u.nickname,s.local_date,s.score,s.timezone_snapshot from fatigue_survey s "
                        + "left join `user` u on u.id=s.user_id where s.local_date between ? and ? and s.score between ? and ?";
                args.addAll(List.of(range.from(), range.to(), bucket * 20, bucket == 4 ? 100 : bucket * 20 + 19));
                order = "user_id desc,local_date desc";
            }
            case "fatigue/completion" -> {
                LocalDate day = day(selector, range);
                sql = "select e.user_id,u.nickname,e.local_date,s.score,case when s.id is not null then 'completed' "
                        + "when k.user_id is not null then 'skipped' else 'pending' end status from (" + analytics.eligibleDays() + ") e "
                        + "left join fatigue_survey s on s.user_id=e.user_id and s.local_date=e.local_date "
                        + "left join fatigue_survey_skip k on k.user_id=e.user_id and k.local_date=e.local_date "
                        + "left join `user` u on u.id=e.user_id where 1=1";
                args.addAll(List.of(day, day, day, day));
                sql += switch (series) {
                    case "completed", "rate" -> " and s.id is not null";
                    case "skipped" -> " and k.user_id is not null and s.id is null";
                    case "expected", "" -> "";
                    default -> throw new BusinessException(400, "invalid completion series");
                };
            }
            case "ops/growth" -> {
                LocalDate day = day(selector, range);
                sql = "select id user_id,nickname,status,created_at from `user` where created_at<?";
                args.add(day.plusDays(1).atStartOfDay());
                if (!"totalUsers".equals(series)) { sql += " and created_at>=?"; args.add(day.atStartOfDay()); }
            }
            case "ops/retention" -> {
                LocalDate day = day(selector, range);
                if (List.of("dau", "wau").contains(series)) {
                    sql = "select u.id user_id,u.nickname,u.status,max(a.activity_date) last_active_date from user_activity_daily a "
                            + "join `user` u on u.id=a.user_id where a.activity_date between ? and ? group by u.id,u.nickname,u.status";
                    args.addAll(List.of("wau".equals(series) ? day.minusDays(6) : day, day));
                } else if (List.of("nextDayRate", "day7Rate", "cohort").contains(series)) {
                    sql = "select u.id user_id,u.nickname,u.status,u.created_at from `user` u where u.created_at>=? and u.created_at<?";
                    args.addAll(List.of(day.atStartOfDay(), day.plusDays(1).atStartOfDay()));
                    if (!"cohort".equals(series)) {
                        sql += " and exists (select 1 from user_activity_daily a where a.user_id=u.id and a.activity_date=?)";
                        args.add(day.plusDays("day7Rate".equals(series) ? 7 : 1));
                    }
                } else throw new BusinessException(400, "invalid retention series");
            }
            case "ops/status-distribution" -> {
                if (!List.of("active", "disabled", "unverified").contains(selector)) throw new BusinessException(400, "invalid status");
                sql = "select id user_id,nickname,status,email_verified_at,created_at from `user` where deleted_at is null "
                        + "and created_at>=? and created_at<? and (case when status='disabled' then 'disabled' "
                        + "when email_verified_at is null then 'unverified' else 'active' end)=?";
                args.addAll(List.of(range.start(), range.end(), selector));
            }
            case "collab/funnel" -> {
                sql = "select t.id task_id,t.team_id,t.title,t.status,t.created_at from team_task t "
                        + "where t.deleted_at is null and t.created_at>=? and t.created_at<?";
                args.addAll(List.of(range.start(), range.end()));
                sql += switch (selector) {
                    case "created" -> "";
                    case "accepted" -> " and (t.status='completed' or exists (select 1 from team_task_assignee a where a.task_id=t.id "
                            + "and (a.accepted_at is not null or a.completed_at is not null)))";
                    case "completed" -> " and t.status='completed'";
                    default -> throw new BusinessException(400, "invalid funnel stage");
                };
            }
            case "collab/completion-trend" -> {
                LocalDate day = day(selector, range);
                sql = "select * from (" + analytics.settledTasks() + ") t where settled_at>=? and settled_at<?";
                args.addAll(List.of(day.atStartOfDay(), day.plusDays(1).atStartOfDay()));
                sql += switch (series) {
                    case "onTime", "onTimeRate" -> " and status='completed' and (deadline_time is null or settled_at<=deadline_time)";
                    case "overdue" -> " and status='completed' and deadline_time is not null and settled_at>deadline_time";
                    case "cancelled" -> " and status='cancelled'";
                    case "rate" -> " and status='completed'";
                    default -> throw new BusinessException(400, "invalid settlement series");
                };
            }
            case "collab/team-distribution" -> {
                String condition = switch (selector) {
                    case "0" -> "members=0"; case "1-5" -> "members between 1 and 5";
                    case "6-10" -> "members between 6 and 10"; case "11-20" -> "members between 11 and 20";
                    case "21+" -> "members>20"; default -> throw new BusinessException(400, "invalid team bucket");
                };
                sql = "select * from (select t.id team_id,t.name,t.status,count(m.id) members from team t "
                        + "left join team_member m on m.team_id=t.id and m.status='active' where t.deleted_at is null "
                        + "and t.created_at>=? and t.created_at<? group by t.id,t.name,t.status) teams where " + condition;
                args.addAll(List.of(range.start(), range.end()));
            }
            case "ai/cost-trend", "ai/feature-distribution" -> {
                sql = "select id usage_log_id,user_id,team_id,feature_type,status,model_name,input_tokens,output_tokens,"
                        + "estimated_cost,key_id,switch_count,failure_kind,created_at from ai_usage_log where created_at>=? and created_at<?";
                if ("cost-trend".equals(metric)) {
                    LocalDate day = day(selector, range);
                    args.addAll(List.of(day.atStartOfDay(), day.plusDays(1).atStartOfDay()));
                } else {
                    args.addAll(List.of(range.start(), range.end(), selector));
                    sql += " and feature_type=?";
                }
            }
            case "ai/key-health" -> {
                sql = "select id,key_id,success,switched,created_at from ai_key_call_log where created_at>=? and created_at<?";
                args.addAll(List.of(range.start(), range.end()));
                if ("environment".equals(selector)) sql += " and key_id is null";
                else { sql += " and key_id=?"; args.add(positiveId(selector)); }
            }
            case "security/register-analysis" -> {
                sql = "select e.id,e.user_id,u.nickname,e.ip_segment,e.created_at from security_event e "
                        + "left join `user` u on u.id=e.user_id where e.event_type='registration' and e.created_at>=? and e.created_at<? and e.ip_segment=?";
                LocalDate day = day(series, range);
                args.addAll(List.of(day.atStartOfDay(), day.plusDays(1).atStartOfDay(), selector));
            }
            default -> throw new BusinessException(404, "detail dataset not found");
        }
        Map<String, Object> result = paged(sql, args, order, page, size);
        result.putAll(range.metadata());
        if ("fatigue".equals(group)) result.put("timezone", "user-local-date");
        result.put("dataset", group + "/" + metric);
        result.put("selector", selector);
        result.put("series", series);
        return result;
    }

    // One repeatable-read snapshot prevents skipped or duplicated rows between export pages.
    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public Map<String, Object> export(String group, String metric, AnalyticsRange range, String selector, String series, Long userId) {
        List<Map<String, Object>> all = new ArrayList<>();
        Map<String, Object> data;
        int page = 1;
        do {
            data = selector != null ? details(group, metric, range, selector, series, page, 100)
                    : switch (group + "/" + metric) {
                        case "security/register-analysis" -> analytics.registrations(range, page, 100);
                        case "security/login-anomalies" -> analytics.anomalies(range, page, 100, userId);
                        case "ai/quota-alerts" -> analytics.quotaAlerts(range, page, 100);
                        default -> analytics.view(group, metric, range, page, 100);
                    };
            if (((Number) data.getOrDefault("total", 0)).longValue() > EXPORT_LIMIT)
                throw new BusinessException(400, "导出超过 20,000 条，请缩小日期范围");
            @SuppressWarnings("unchecked") List<Map<String, Object>> items = (List<Map<String, Object>>) data.get("items");
            all.addAll(items);
            if (all.size() > EXPORT_LIMIT) throw new BusinessException(400, "导出超过 20,000 条，请缩小日期范围");
            page++;
        } while (all.size() < ((Number) data.getOrDefault("total", all.size())).longValue());
        Map<String, Object> result = new LinkedHashMap<>(data);
        result.put("items", all);
        result.put("exportedAt", Instant.now().toString());
        result.put("dataset", group + "/" + metric);
        return result;
    }

    private Map<String, Object> paged(String sql, List<?> args, String order, int page, int size) {
        if (page < 1 || page > 1000000 || size < 1 || size > 100) throw new BusinessException(400, "invalid pagination");
        long total = jdbc.queryForObject("select count(*) from (" + sql + ") filtered", Long.class, args.toArray());
        List<Object> parameters = new ArrayList<>(args);
        parameters.add(size);
        parameters.add((page - 1) * size);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("items", analytics.query("select * from (" + sql + ") filtered order by " + order + " limit ? offset ?", parameters.toArray()));
        result.put("total", total);
        result.put("page", page);
        result.put("size", size);
        return result;
    }

    private static LocalDate day(String value, AnalyticsRange range) {
        try {
            LocalDate day = LocalDate.parse(value);
            if (day.isBefore(range.from()) || day.isAfter(range.to())) throw new IllegalArgumentException();
            return day;
        } catch (RuntimeException ex) { throw new BusinessException(400, "selected day is outside the range"); }
    }

    private static long positiveId(String value) {
        try { long id = Long.parseLong(value); if (id > 0) return id; }
        catch (NumberFormatException ignored) { }
        throw new BusinessException(400, "invalid id");
    }
}
