package com.dayliane.admin;

import com.dayliane.ai.AiQuotaService;
import com.dayliane.ai.AiService;
import com.dayliane.common.BusinessException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;

import java.time.LocalDate;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest(properties = "spring.datasource.url=jdbc:h2:mem:admin_observability;MODE=MySQL;DATABASE_TO_LOWER=TRUE;CASE_INSENSITIVE_IDENTIFIERS=TRUE;DB_CLOSE_DELAY=-1")
@ActiveProfiles("test")
@AutoConfigureMockMvc
class AdminObservabilityIntegrationTests {
    @Autowired private AdminAnalyticsService analytics;
    @Autowired private AiQuotaService quota;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private AdminInspectionService inspection;
    @Autowired private AdminService admin;
    @Autowired private AdminSystemStatusService system;
    @Autowired private AiService ai;
    @Autowired private MockMvc mvc;

    @BeforeEach
    void cleanObservabilityData() {
        for (String table : List.of("security_event", "user_activity_daily", "ai_quota_alert", "ai_quota_daily", "ai_quota_override",
                "ai_key_call_log", "ai_model_price", "ai_usage_log", "fatigue_survey_skip", "fatigue_survey",
                "fatigue_daily_summary", "user_fatigue_profile", "team_task_assignee", "team_task_event", "team_task",
                "team_member", "team", "admin_operation_log", "admin_user")) {
            jdbc.update("delete from " + table);
        }
        jdbc.update("update ai_quota_setting set enabled=false,global_daily_limit=10000,user_daily_limit=100,"
                + "team_daily_limit=1000,revision=revision+1 where id=1");
        jdbc.update("delete from `user`");
    }

    @Test
    void drilldownQueriesAndWorkspaceSectionsAreExecutable() {
        long userId = insertUser("details@example.com");
        AnalyticsRange range = new AnalyticsRange(LocalDate.of(2020, 1, 1), LocalDate.of(2020, 1, 2));
        List<String[]> cases = List.of(
                new String[]{"fatigue", "distribution", "80-100", "value"},
                new String[]{"fatigue", "completion", "2020-01-01", "expected"},
                new String[]{"fatigue", "completion", "2020-01-01", "completed"},
                new String[]{"fatigue", "completion", "2020-01-01", "skipped"},
                new String[]{"ops", "growth", "2020-01-01", "newUsers"},
                new String[]{"ops", "growth", "2020-01-01", "totalUsers"},
                new String[]{"ops", "retention", "2020-01-01", "dau"},
                new String[]{"ops", "retention", "2020-01-01", "wau"},
                new String[]{"ops", "retention", "2020-01-01", "day7Rate"},
                new String[]{"ops", "status-distribution", "active", "value"},
                new String[]{"collab", "funnel", "accepted", "value"},
                new String[]{"collab", "completion-trend", "2020-01-01", "overdue"},
                new String[]{"collab", "completion-trend", "2020-01-01", "onTime"},
                new String[]{"collab", "completion-trend", "2020-01-01", "cancelled"},
                new String[]{"collab", "team-distribution", "1-5", "value"},
                new String[]{"ai", "cost-trend", "2020-01-01", "calls"},
                new String[]{"ai", "feature-distribution", "daily_plan", "value"},
                new String[]{"ai", "key-health", "environment", "value"},
                new String[]{"security", "register-analysis", "127.0.0.0/24", "2020-01-01"});
        for (String[] item : cases) assertThatCode(() -> inspection.details(item[0], item[1], range, item[2], item[3], 1, 20))
                .as(String.join("/", item)).doesNotThrowAnyException();
        for (String section : List.of("teams", "fatigue", "ai", "risks", "audit"))
            assertThat(inspection.workspace(userId, section, 1, 20)).containsEntry("total", 0L);
        assertThatThrownBy(() -> inspection.details("collab", "funnel", range, "' or 1=1", "", 1, 20)).isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> inspection.details("ops", "growth", range, "2020-02-01", "", 1, 20)).isInstanceOf(BusinessException.class);
    }

    @Test
    @SuppressWarnings("unchecked")
    void fullFilterSummaryDoesNotDependOnTheCurrentPage() {
        for (int i = 0; i < 23; i++) insertUser("summary" + i + "@example.com");
        jdbc.update("update `user` set status='disabled' where email='summary22@example.com'");
        Map<String, Object> result = admin.adminList("users", 1, 10, "summary", null, null, null);
        assertThat(result).containsEntry("total", 23);
        assertThat((Map<String, Object>) result.get("summary")).containsEntry("active", 22L).containsEntry("disabled", 1L);
        assertThat((List<?>) result.get("list")).hasSize(10);
        assertThat(admin.adminList("users", 1, 10, "summary", "disabled", null, null)).containsEntry("total", 1);
    }

    @Test
    void teamQuotaChargesOnlyTheExplicitTeamAndPersonalCallsChargeNone() {
        long user = insertUser("teams@example.com");
        long first = insertTeam(user, "FIRST"), second = insertTeam(user, "SECOND");
        quota.reserve(user);
        assertThat(jdbc.queryForObject("select count(*) from ai_quota_daily where scope_type='team'", Integer.class)).isZero();
        quota.reserve(user, first);
        assertThat(jdbc.queryForObject("select calls from ai_quota_daily where scope_type='team' and subject_id=?", Integer.class, first)).isEqualTo(1);
        assertThat(jdbc.queryForObject("select count(*) from ai_quota_daily where scope_type='team' and subject_id=?", Integer.class, second)).isZero();
        assertThatThrownBy(() -> quota.reserve(user, second + 9999)).isInstanceOf(BusinessException.class);
        assertThat(jdbc.queryForObject("select calls from ai_quota_daily where scope_type='global'", Integer.class)).isEqualTo(2);
    }

    @Test
    void individualOverrideAndNearLimitAlertsAreEnforcedAndDeduplicated() {
        long user = insertUser("override@example.com");
        jdbc.update("update ai_quota_setting set enabled=true,user_daily_limit=1,warning_percent=50 where id=1");
        jdbc.update("insert into ai_quota_override values ('user',?,2,true)", user);
        quota.reserve(user);
        quota.reserve(user);
        assertThatThrownBy(() -> quota.reserve(user)).isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> quota.reserve(user)).isInstanceOf(BusinessException.class);
        assertThat(jdbc.queryForObject("select count(*) from ai_quota_alert where scope_type='user' and alert_kind='near_limit'", Integer.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject("select count(*) from ai_quota_alert where scope_type='user' and alert_kind='exceeded'", Integer.class)).isEqualTo(1);
        assertThat(quota.settings()).containsKeys("usage", "overrides", "warningPercent");
    }

    @Test
    @SuppressWarnings("unchecked")
    void riskReviewIsVersionedAuditedAndRemovesResolvedRiskFromTheUserBadge() {
        long user = insertUser("risk@example.com");
        long adminId = insertAdmin();
        long event = insertRisk(user);
        assertThat(inspection.review(adminId, event, Map.of("status", "false_positive", "note", "Verified source", "revision", 0), "127.0.0.1", "test"))
                .containsEntry("reviewStatus", "false_positive").containsEntry("revision", 1L);
        assertThatThrownBy(() -> inspection.review(adminId, event, Map.of("status", "confirmed", "note", "stale", "revision", 0), "", ""))
                .isInstanceOf(BusinessException.class).satisfies(ex -> assertThat(((BusinessException) ex).getCode()).isEqualTo(409));
        assertThat(jdbc.queryForObject("select count(*) from admin_operation_log where action='review_risk'", Integer.class)).isEqualTo(1);
        List<Map<String, Object>> users = (List<Map<String, Object>>) admin.adminList("users", 1, 20).get("list");
        assertThat(((Number) users.get(0).get("riskCount")).longValue()).isZero();
        assertThat(inspection.workspace(user, "audit", 1, 20)).containsEntry("total", 1L);
        admin.adminSetUserStatus(adminId, user, "disabled", "", "", "Risk confirmed");
        assertThat(jdbc.queryForObject("select after_data from admin_operation_log where action='set_user_status'", String.class)).contains("Risk confirmed");
    }

    @Test
    @SuppressWarnings("unchecked")
    void exportIncludesEveryPageAndUsesConsistentUserFilter() {
        long user = insertUser("export@example.com");
        long other = insertUser("other@example.com");
        for (int i = 0; i < 121; i++) insertRisk(user);
        insertRisk(other);
        LocalDate day = LocalDate.now(java.time.ZoneOffset.UTC);
        Map<String, Object> result = inspection.export("security", "login-anomalies", new AnalyticsRange(day, day), null, "", user);
        List<Map<String, Object>> rows = (List<Map<String, Object>>) result.get("items");
        assertThat(rows).hasSize(121);
        assertThat(rows.stream().map(row -> row.get("id")).distinct().count()).isEqualTo(121);
        assertThat(rows).allSatisfy(row -> assertThat(row).containsEntry("userId", user));
        assertThat(result).containsKeys("exportedAt", "timezone", "dateFrom", "dateTo");
    }

    @Test
    @SuppressWarnings("unchecked")
    void registrationPaginationKeepsFullRangeTotals() {
        long user = insertUser("register-groups@example.com");
        for (int i = 0; i < 25; i++) jdbc.update("insert into security_event (user_id,event_type,ip_address,ip_segment,risk_level,reason) "
                + "values (?,'registration','127.0.0.1',?,'low','self_registration')", user, "10.0." + i + ".0/24");
        LocalDate day = LocalDate.now(java.time.ZoneOffset.UTC);
        Map<String, Object> result = analytics.registrations(new AnalyticsRange(day, day), 2, 20);
        assertThat(result).containsEntry("total", 25L);
        assertThat((List<?>) result.get("items")).hasSize(5);
        assertThat(((Number) ((Map<?, ?>) result.get("summary")).get("registrations")).longValue()).isEqualTo(25);
    }

    @Test
    @SuppressWarnings("unchecked")
    void aiLogsIncludeMetadataAndFullFilterStatusSummary() {
        long user = insertUser("ai-log@example.com");
        for (int i = 0; i < 3; i++) jdbc.update("insert into ai_usage_log (user_id,feature_type,status,model_name,input_tokens,output_tokens,estimated_cost,switch_count,failure_kind)"
                + "values (?,'daily_plan',?,'test-model',100,50,0.0123,2,?)", user, i == 2 ? "rejected" : "success", i == 2 ? "quota" : null);
        Map<String, Object> result = ai.usageLogs(1, 1, String.valueOf(user), null, null, null, null);
        assertThat((Map<?, ?>) result.get("summary")).hasSize(2);
        Map<String, Object> row = ((List<Map<String, Object>>) result.get("list")).get(0);
        assertThat(row).containsEntry("modelName", "test-model").containsEntry("switchCount", 2);
        assertThat(inspection.aiLog(((Number) row.get("id")).longValue())).containsEntry("failureKind", "quota");
    }

    @Test
    void runtimeStatusReportsEnvironmentAndBoundedWindow() {
        assertThat(system.status()).containsEntry("environment", "test").containsEntry("monitorStatus", "unconfigured")
                .containsKeys("recentApi", "telemetry");
        RecentOperationalMetrics recent = new RecentOperationalMetrics();
        recent.recordRequest(25, false);
        recent.recordRequest(75, true);
        assertThat(recent.snapshot()).containsEntry("requests", 2L).containsEntry("errors", 1L)
                .containsEntry("averageLatencyMs", 50L).containsEntry("maxLatencyMs", 75L);
    }

    @Test
    void newEndpointsStillRequireAdminAuthentication() throws Exception {
        for (String path : List.of("/views/ops/growth/details?dateFrom=2020-01-01&dateTo=2020-01-02&selector=2020-01-01",
                "/views/ops/growth/export?dateFrom=2020-01-01&dateTo=2020-01-02", "/users/1/workspace/teams",
                "/ai/quota/usage", "/ai/usage-logs/1", "/ops/system-status")) {
            mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get("/api/v1/admin" + path))
                    .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isUnauthorized());
        }
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put("/api/v1/admin/security/events/1/review")
                .contentType("application/json").content("{\"status\":\"resolved\",\"note\":\"test\",\"revision\":0}"))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isUnauthorized());
    }

    private long insertTeam(long owner, String code) {
        jdbc.update("insert into team (name,invite_code,invite_code_expire_at,owner_id) values (?,?,utc_timestamp(),?)", code, code, owner);
        long id = jdbc.queryForObject("select id from team where invite_code=?", Long.class, code);
        jdbc.update("insert into team_member (team_id,user_id,role) values (?,?,'owner')", id, owner);
        return id;
    }

    private long insertAdmin() {
        jdbc.update("insert into admin_user (username,password_hash,role) values ('reviewer','hash','super_admin')");
        return jdbc.queryForObject("select id from admin_user where username='reviewer'", Long.class);
    }

    private long insertRisk(long user) {
        jdbc.update("insert into security_event (user_id,event_type,ip_address,ip_segment,risk_level,reason) "
                + "values (?,'login_failure','127.0.0.1','127.0.0.0/24','high','frequent_failures')", user);
        return jdbc.queryForObject("select max(id) from security_event", Long.class);
    }

    @Test
    void allNewViewQueriesReturnEmptyResultsWithoutErrors() {
        AnalyticsRange range = new AnalyticsRange(LocalDate.of(2020, 1, 1), LocalDate.of(2020, 1, 2));
        List<String[]> views = List.of(
                new String[]{"fatigue", "distribution"},
                new String[]{"fatigue", "completion"},
                new String[]{"fatigue", "high-load-users"},
                new String[]{"ops", "growth"},
                new String[]{"ops", "retention"},
                new String[]{"ops", "status-distribution"},
                new String[]{"collab", "funnel"},
                new String[]{"collab", "completion-trend"},
                new String[]{"collab", "team-distribution"},
                new String[]{"ai", "cost-trend"},
                new String[]{"ai", "feature-distribution"},
                new String[]{"ai", "key-health"}
        );

        views.forEach(view -> assertThatCode(() -> analytics.view(view[0], view[1], range, 1, 20))
                .as(view[0] + "/" + view[1])
                .doesNotThrowAnyException());
        assertThatCode(() -> analytics.anomalies(range, 1, 20, null)).doesNotThrowAnyException();
        assertThatCode(() -> analytics.registrations(range)).doesNotThrowAnyException();
        assertThatCode(() -> analytics.quotaAlerts(range, 1, 20)).doesNotThrowAnyException();
    }

    @Test
    @SuppressWarnings("unchecked")
    void fatigueViewsUseThePersistedUserDayAndSurveyData() {
        LocalDate day = LocalDate.now(java.time.ZoneOffset.UTC).minusDays(1);
        long userId = insertUser("fatigue-observer@example.com");
        jdbc.update("insert into user_fatigue_profile (user_id,capacity_75,fatigue_tracking_enabled,survey_enabled) "
                        + "values (?,18,true,true)", userId);
        jdbc.update("insert into fatigue_daily_summary (user_id,local_date,timezone_snapshot,planned_load,"
                        + "completed_load,predicted_score) values (?,?,'Asia/Shanghai',20,18,80)",
                userId, day);
        jdbc.update("insert into fatigue_survey (user_id,local_date,timezone_snapshot,score,weights_snapshot,"
                        + "completed_load_snapshot,capacity_before) values (?,?,'Asia/Shanghai',85,'{}',18,18)",
                userId, day);

        Map<String, Object> distribution = analytics.view("fatigue", "distribution",
                new AnalyticsRange(day, day), 1, 20);
        List<Map<String, Object>> distributionItems = (List<Map<String, Object>>) distribution.get("items");
        assertThat(distributionItems).anySatisfy(item -> assertThat(item)
                .containsEntry("name", "80-100")
                .containsEntry("value", 1L));

        Map<String, Object> completion = analytics.view("fatigue", "completion",
                new AnalyticsRange(day, day), 1, 20);
        List<Map<String, Object>> completionItems = (List<Map<String, Object>>) completion.get("items");
        assertThat(completionItems).singleElement().satisfies(item -> assertThat(item)
                .containsEntry("expected", 1L)
                .containsEntry("completed", 1L)
                .containsEntry("rate", new BigDecimal("100.0")));

        Map<String, Object> highLoad = analytics.view("fatigue", "high-load-users",
                new AnalyticsRange(day, day), 1, 20);
        assertThat(highLoad).containsEntry("total", 1L);
        assertThat((List<Map<String, Object>>) highLoad.get("items"))
                .singleElement()
                .satisfies(item -> assertThat(item).containsEntry("userId", userId));
    }

    @Test
    void quotaRejectsTheSecondCallAndPersistsAnAlert() {
        long userId = insertUser("quota-observer@example.com");
        jdbc.update("update ai_quota_setting set enabled=true,global_daily_limit=1,user_daily_limit=1,"
                + "team_daily_limit=100,revision=revision+1 where id=1");

        quota.reserve(userId);

        assertThatThrownBy(() -> quota.reserve(userId))
                .isInstanceOf(BusinessException.class)
                .satisfies(error -> {
                    BusinessException exception = (BusinessException) error;
                    assertThat(exception.getCode()).isEqualTo(429);
                    assertThat(exception).hasMessageContaining("AI_QUOTA_EXCEEDED");
                });
        assertThat(jdbc.queryForObject("select calls from ai_quota_daily where scope_type='global' and subject_id=0",
                Integer.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject("select count(*) from ai_quota_alert where user_id=? and alert_kind='exceeded'", Integer.class, userId))
                .isEqualTo(1);
    }

    private long insertUser(String email) {
        jdbc.update("insert into `user` (email,email_verified_at,password_hash,nickname,avatar_url,timezone,status) "
                        + "values (?,utc_timestamp(),'hash','Observer','', 'Asia/Shanghai','active')", email);
        return jdbc.queryForObject("select id from `user` where email=?", Long.class, email);
    }
}
