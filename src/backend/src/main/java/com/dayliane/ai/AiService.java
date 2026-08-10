package com.dayliane.ai;

import com.dayliane.admin.AdminService;
import com.dayliane.common.BusinessException;
import com.dayliane.user.UserService;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.scheduling.annotation.Scheduled;

import java.net.IDN;
import java.net.InetAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

@Service
public class AiService {
    private static final int MAX_INPUT_LENGTH = 8000;
    private static final int MAX_LOG_LENGTH = 12000;
    private static final Pattern PHONE_PATTERN = Pattern.compile("(?<!\\d)(1\\d{2})\\d{4}(\\d{4})(?!\\d)");
    private static final Pattern EMAIL_PATTERN = Pattern.compile("([A-Za-z0-9._%+-])[A-Za-z0-9._%+-]*@([A-Za-z0-9.-]+\\.[A-Za-z]{2,})");
    private final JdbcTemplate jdbc;
    private final AdminService adminService;
    private final UserService userService;
    private final ObjectMapper objectMapper;
    private final HttpClient httpClient;
    private final boolean defaultEnabled;
    private final String defaultProvider;
    private final String defaultModel;
    private final String defaultApiBaseUrl;
    private final String apiKey;
    private final Set<String> allowedApiHosts;

    public AiService(JdbcTemplate jdbc, AdminService adminService, UserService userService, ObjectMapper objectMapper,
                     @Value("${app.ai.enabled:false}") boolean defaultEnabled,
                     @Value("${app.ai.provider:}") String defaultProvider,
                      @Value("${app.ai.model:}") String defaultModel,
                      @Value("${app.ai.api-base-url:}") String defaultApiBaseUrl,
                      @Value("${app.ai.api-key:}") String apiKey,
                      @Value("${app.ai.allowed-hosts:}") String allowedHosts) {
        this.jdbc = jdbc;
        this.adminService = adminService;
        this.userService = userService;
        this.objectMapper = objectMapper;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .followRedirects(HttpClient.Redirect.NEVER)
                .build();
        this.defaultEnabled = defaultEnabled;
        this.defaultProvider = defaultProvider;
        this.defaultModel = defaultModel;
        this.defaultApiBaseUrl = defaultApiBaseUrl;
        this.apiKey = apiKey;
        this.allowedApiHosts = allowedApiHosts(allowedHosts, defaultApiBaseUrl);
    }

    public Map<String, Object> parseSchedule(long userId, String text, boolean recordUsage) {
        requireText(text);
        ZoneId zone = userZone(userId);
        String now = OffsetDateTime.now(zone).toString();
        String input = "Current time: " + now + "\nTimezone: " + zone.getId() + "\nUser text: " + text;
        return suggest(userId, "schedule_parse", input, "Return JSON only: {\"title\":\"\",\"groupName\":\"\",\"timeType\":\"\",\"startTime\":\"\",\"endTime\":\"\",\"deadlineTime\":\"\",\"remindAt\":\"\",\"description\":\"\"}. Resolve relative dates like 明天, 后天, 下周 using Current time and Timezone. Output all date-time fields as local values in the provided Timezone using format yyyy-MM-dd'T'HH:mm, without UTC conversion. Infer groupName from explicit user instruction first: phrases like 放X, 放到X, 归到X, 分到X mean groupName must be X. If there is no explicit group instruction, infer groupName from the task topic in Chinese, for example 学习/读书/图书馆/上课/考试 -> 学习, 开会/项目/工作/汇报 -> 工作, 运动/健身/跑步 -> 运动, 吃饭/购物/家务/生活 -> 生活. timeType must be one of point_event, deadline_task, duration_task. Use duration_task when the text contains both a start time and an end time. Use deadline_task for a due time, and point_event for a single occurrence time. Infer remindAt only when the user asks for a reminder. All user-facing text values must be in Chinese. Extract a schedule draft from this context:", recordUsage);
    }

    public Map<String, Object> breakdownTeamTask(long userId, String text, boolean recordUsage) {
        requireText(text);
        return suggest(userId, "team_task_breakdown", text, "Return JSON only: {\"tasks\":[{\"title\":\"\",\"description\":\"\",\"deadlineTime\":\"\"}]}. Break this team task into actionable tasks. deadlineTime may be empty or an ISO local date-time:", recordUsage);
    }

    public Map<String, Object> dailyPlan(long userId, boolean recordUsage) {
        String context = dailyContext(userId);
        return suggest(userId, "daily_plan", context, "Return JSON only: {\"suggestion\":\"\"}. The suggestion must be written in Chinese. Give a concise daily plan using only this user's items:\n", recordUsage);
    }

    public Map<String, Object> optimizeTaskDescription(long userId, String text, boolean recordUsage) {
        requireText(text);
        return suggest(userId, "task_description_optimize", text, "Return JSON only: {\"description\":\"\"}. Improve this task description while preserving its intent:", recordUsage);
    }

    public Map<String, Object> configView() {
        Map<String, Object> config = latestConfig();
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("provider", config == null ? defaultProvider : config.get("provider"));
        out.put("modelName", config == null ? defaultModel : config.get("modelName"));
        out.put("apiBaseUrl", config == null ? defaultApiBaseUrl : config.get("apiBaseUrl"));
        out.put("enabled", config == null ? defaultEnabled : config.get("enabled"));
        out.put("remark", config == null ? "" : config.get("remark"));
        out.put("apiKeyMasked", maskApiKey());
        if (config != null) {
            out.put("id", config.get("id"));
            out.put("createdAt", config.get("createdAt"));
            out.put("updatedAt", config.get("updatedAt"));
        }
        return out;
    }

    @Transactional
    public Map<String, Object> updateConfig(long adminId, Map<String, Object> req, String ipAddress, String userAgent) {
        Map<String, Object> before = configView();
        String provider = valueOr(req, "provider", String.valueOf(before.get("provider")));
        String modelName = valueOr(req, "modelName", String.valueOf(before.get("modelName")));
        String apiBaseUrl = valueOr(req, "apiBaseUrl", String.valueOf(before.get("apiBaseUrl")));
        boolean enabled = booleanOr(req, "enabled", Boolean.TRUE.equals(before.get("enabled")));
        String remark = valueOr(req, "remark", String.valueOf(before.get("remark")));
        validateConfig(provider, modelName, apiBaseUrl);
        jdbc.update("insert into ai_config (provider,model_name,api_base_url,api_key_masked,enabled,remark) values (?,?,?,?,?,?)",
                provider, modelName, trimBaseUrl(apiBaseUrl), maskApiKey(), enabled, limit(remark, 4000));
        Map<String, Object> after = configView();
        adminService.writeAdminOperationLog(adminId, "update_ai_config", "ai_config", null, safeConfig(before), safeConfig(after), ipAddress, userAgent);
        return after;
    }

    @Transactional
    public Map<String, Object> updateEnabled(long adminId, boolean enabled, String ipAddress, String userAgent) {
        Map<String, Object> before = configView();
        String provider = String.valueOf(before.get("provider"));
        String modelName = String.valueOf(before.get("modelName"));
        String apiBaseUrl = String.valueOf(before.get("apiBaseUrl"));
        if (enabled) validateConfig(provider, modelName, apiBaseUrl);
        jdbc.update("insert into ai_config (provider,model_name,api_base_url,api_key_masked,enabled,remark) values (?,?,?,?,?,?)",
                provider, modelName, trimBaseUrl(apiBaseUrl), maskApiKey(), enabled, limit(String.valueOf(before.get("remark")), 4000));
        Map<String, Object> after = configView();
        adminService.writeAdminOperationLog(adminId, "set_ai_enabled", "ai_config", null, safeConfig(before), safeConfig(after), ipAddress, userAgent);
        return after;
    }

    public Map<String, Object> usageLogs(int page, int size, String userId, String featureType, String status, String dateFrom, String dateTo) {
        StringBuilder where = new StringBuilder(" from ai_usage_log where 1=1");
        List<Object> params = new ArrayList<>();
        if (!blank(userId)) { try { where.append(" and user_id=?"); params.add(Long.parseLong(userId)); } catch (NumberFormatException ex) { throw new BusinessException(400, "userId is invalid"); } }
        if (!blank(featureType)) { where.append(" and feature_type=?"); params.add(featureType); }
        if (!blank(status)) { where.append(" and status=?"); params.add(status); }
        addDateFilters(where, params, dateFrom, dateTo);
        Integer totalValue = jdbc.queryForObject("select count(*)" + where, Integer.class, params.toArray());
        int total = totalValue == null ? 0 : totalValue;
        int safePage = Math.max(1, page);
        int safeSize = Math.min(100, Math.max(1, size));
        String sql = "select id,user_id userId,feature_type featureType,input_text inputText,output_text outputText,status,error_message errorMessage,created_at createdAt" +
                where + " order by created_at desc,id desc limit ? offset ?";
        params.add(safeSize);
        params.add((safePage - 1) * safeSize);
        List<Map<String, Object>> rows = jdbc.query(sql, (rs, i) -> {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("id", rs.getLong("id")); row.put("userId", rs.getObject("userId")); row.put("featureType", rs.getString("featureType"));
            row.put("inputText", rs.getString("inputText")); row.put("outputText", rs.getString("outputText")); row.put("status", rs.getString("status"));
            row.put("errorMessage", rs.getString("errorMessage")); row.put("createdAt", iso(rs.getTimestamp("createdAt"))); return row;
        }, params.toArray());
        return Map.of("list", rows, "total", total, "page", safePage, "size", safeSize);
    }

    public Map<String, Object> usageStats(String dateFrom, String dateTo) {
        StringBuilder sql = new StringBuilder("select feature_type featureType,status,count(*) total from ai_usage_log where 1=1");
        List<Object> params = new ArrayList<>();
        addDateFilters(sql, params, dateFrom, dateTo);
        sql.append(" group by feature_type,status order by feature_type,status");
        List<Map<String, Object>> rows = jdbc.query(sql.toString(), (rs, i) -> Map.of("featureType", rs.getString("featureType"), "status", rs.getString("status"), "total", rs.getLong("total")), params.toArray());
        return Map.of("list", rows);
    }

    public Map<String, Object> test() {
        String raw = call("Reply with JSON only: {\"suggestion\":\"ok\"}");
        return Map.of("ok", true, "rawText", limit(raw, MAX_LOG_LENGTH));
    }

    private Map<String, Object> suggest(long userId, String featureType, String input, String instruction, boolean recordUsage) {
        String safeInput = limit(input, MAX_INPUT_LENGTH);
        try {
            String raw = call(instruction + "\n" + safeInput);
            Map<String, Object> result = formatResult(featureType, safeInput, raw);
            log(userId, featureType, safeInput, raw, "success", null, recordUsage);
            return result;
        } catch (BusinessException ex) {
            log(userId, featureType, safeInput, null, "failed", ex.getMessage(), recordUsage);
            throw ex;
        }
    }

    private String call(String prompt) {
        Map<String, Object> config = effectiveConfig();
        if (!Boolean.TRUE.equals(config.get("enabled"))) throw new BusinessException(400, "AI service is disabled");
        String baseUrl = String.valueOf(config.get("apiBaseUrl"));
        String model = String.valueOf(config.get("modelName"));
        if (blank(apiKey) || blank(baseUrl) || blank(model)) throw new BusinessException(400, "AI configuration is incomplete");
        try {
            URI validatedBaseUrl = validateApiBaseUrl(baseUrl);
            String body = objectMapper.writeValueAsString(Map.of("model", model, "messages", List.of(Map.of("role", "system", "content", "You are a helpful scheduling assistant. Follow the requested JSON schema exactly. All user-facing text in the JSON response must be written in Chinese."), Map.of("role", "user", "content", prompt)), "temperature", 0.2));
            HttpRequest request = HttpRequest.newBuilder(URI.create(trimBaseUrl(validatedBaseUrl.toString()) + "/chat/completions"))
                    .timeout(Duration.ofSeconds(30)).header("Content-Type", "application/json").header("Authorization", "Bearer " + apiKey)
                    .POST(HttpRequest.BodyPublishers.ofString(body)).build();
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() < 200 || response.statusCode() >= 300) throw new BusinessException(503, "AI service is unavailable");
            JsonNode content = objectMapper.readTree(response.body()).path("choices").path(0).path("message").path("content");
            if (!content.isTextual() || content.asText().isBlank()) throw new BusinessException(503, "AI service returned an invalid response");
            return content.asText();
        } catch (BusinessException ex) {
            throw ex;
        } catch (Exception ex) {
            throw new BusinessException(503, "AI service is unavailable");
        }
    }

    private Map<String, Object> formatResult(String featureType, String input, String raw) {
        Object parsed = parseJson(raw);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("rawText", limit(raw, MAX_LOG_LENGTH));
        if ("schedule_parse".equals(featureType)) {
            Map<String, Object> draft = parsed instanceof Map<?, ?> map ? mapValue(map, "draft", map) : Map.of();
            Map<String, Object> normalized = new LinkedHashMap<>();
            for (String key : List.of("title", "groupName", "timeType", "startTime", "endTime", "deadlineTime", "remindAt", "description")) normalized.put(key, stringValue(draft.get(key)));
            String timeType = stringValue(normalized.get("timeType"));
            String startTime = stringValue(normalized.get("startTime"));
            String endTime = stringValue(normalized.get("endTime"));
            String deadlineTime = stringValue(normalized.get("deadlineTime"));
            if (!blank(startTime) && !blank(endTime)) normalized.put("timeType", "duration_task");
            else if (!blank(deadlineTime)) normalized.put("timeType", "deadline_task");
            else if (!List.of("point_event", "deadline_task", "duration_task").contains(timeType)) normalized.put("timeType", "point_event");
            if (blank(String.valueOf(normalized.get("title")))) normalized.put("title", input);
            result.put("draft", normalized);
        } else if ("team_task_breakdown".equals(featureType)) {
            List<Map<String, Object>> tasks = tasksFrom(parsed);
            if (tasks.isEmpty()) for (String line : raw.split("\\R")) if (!line.trim().isBlank()) tasks.add(Map.of("title", line.replaceFirst("^[\\s•*\\-\\d.]+", "").trim(), "description", ""));
            result.put("tasks", tasks);
        } else if ("daily_plan".equals(featureType)) {
            String suggestion = parsed instanceof Map<?, ?> map ? stringValue(map.get("suggestion")) : "";
            result.put("suggestion", blank(suggestion) ? raw : suggestion);
        } else {
            String description = parsed instanceof Map<?, ?> map ? stringValue(map.get("description")) : "";
            result.put("description", blank(description) ? raw : description);
        }
        return result;
    }

    private String dailyContext(long userId) {
        List<Map<String, Object>> schedules = jdbc.query("select title,description,time_type timeType,start_time startTime,end_time endTime,deadline_time deadlineTime from schedule where user_id=? and deleted_at is null and status='pending' order by coalesce(deadline_time,start_time,created_at) asc limit 20", (rs, i) -> Map.of("title", rs.getString("title"), "description", nullToEmpty(rs.getString("description")), "timeType", rs.getString("timeType"), "startTime", String.valueOf(rs.getTimestamp("startTime")), "endTime", String.valueOf(rs.getTimestamp("endTime")), "deadlineTime", String.valueOf(rs.getTimestamp("deadlineTime"))), userId);
        List<Map<String, Object>> tasks = jdbc.query("select t.title,t.description,t.start_time startTime,t.deadline_time deadlineTime from team_task t join team_task_assignee a on a.task_id=t.id where a.user_id=? and a.is_active=true and a.status in ('pending','accepted') and t.approval_status='approved' and t.deleted_at is null and t.status in ('active','unassigned') order by coalesce(t.deadline_time,t.start_time,t.created_at) asc limit 20", (rs, i) -> Map.of("title", rs.getString("title"), "description", nullToEmpty(rs.getString("description")), "startTime", String.valueOf(rs.getTimestamp("startTime")), "deadlineTime", String.valueOf(rs.getTimestamp("deadlineTime"))), userId);
        try { return limit(objectMapper.writeValueAsString(Map.of("schedules", schedules, "teamTasks", tasks)), MAX_INPUT_LENGTH); }
        catch (Exception ex) { return ""; }
    }

    private Map<String, Object> effectiveConfig() {
        Map<String, Object> config = latestConfig();
        if (config != null) return config;
        return Map.of("provider", defaultProvider, "modelName", defaultModel, "apiBaseUrl", defaultApiBaseUrl, "enabled", defaultEnabled);
    }

    private Map<String, Object> latestConfig() {
        try {
            return jdbc.queryForObject("select id,provider,model_name modelName,api_base_url apiBaseUrl,enabled,remark,created_at createdAt,updated_at updatedAt from ai_config order by id desc limit 1", (rs, i) -> {
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("id", rs.getLong("id")); row.put("provider", rs.getString("provider")); row.put("modelName", rs.getString("modelName")); row.put("apiBaseUrl", rs.getString("apiBaseUrl")); row.put("enabled", rs.getBoolean("enabled")); row.put("remark", rs.getString("remark")); row.put("createdAt", iso(rs.getTimestamp("createdAt"))); row.put("updatedAt", iso(rs.getTimestamp("updatedAt"))); return row;
            });
        } catch (EmptyResultDataAccessException ex) { return null; }
    }

    private void log(long userId, String featureType, String input, String output, String status, String error, boolean recordContent) {
        String storedInput = recordContent ? limit(maskSensitive(input), MAX_LOG_LENGTH) : null;
        String storedOutput = recordContent ? limit(maskSensitive(output), MAX_LOG_LENGTH) : null;
        jdbc.update("insert into ai_usage_log (user_id,feature_type,input_text,output_text,status,error_message) values (?,?,?,?,?,?)", userId, featureType, storedInput, storedOutput, status, limit(maskSensitive(error), 500));
    }

    @Scheduled(cron = "0 15 3 * * *")
    @Transactional
    public int cleanupExpiredUsageLogs() {
        return jdbc.update("delete from ai_usage_log where created_at < ?", java.sql.Timestamp.from(Instant.now().minus(30, ChronoUnit.DAYS)));
    }

    private Object parseJson(String raw) {
        try { return objectMapper.readValue(stripCodeFence(raw), new TypeReference<Object>() {}); }
        catch (Exception ex) { return null; }
    }

    @SuppressWarnings("unchecked") private Map<String, Object> mapValue(Map<?, ?> source, String key, Map<?, ?> fallback) { Object value = source.get(key); return value instanceof Map<?, ?> map ? (Map<String, Object>) map : (Map<String, Object>) fallback; }
    private List<Map<String, Object>> tasksFrom(Object parsed) {
        Object source = parsed instanceof Map<?, ?> map ? map.get("tasks") : parsed;
        List<Map<String, Object>> tasks = new ArrayList<>();
        if (source instanceof List<?> list) for (Object item : list) { if (item instanceof Map<?, ?> map) { Map<String, Object> task = new LinkedHashMap<>(); task.put("title", stringValue(map.get("title"))); task.put("description", stringValue(map.get("description"))); task.put("deadlineTime", stringValue(map.get("deadlineTime"))); if (!blank(String.valueOf(task.get("title")))) tasks.add(task); } else if (!blank(String.valueOf(item))) tasks.add(Map.of("title", String.valueOf(item), "description", "", "deadlineTime", "")); }
        return tasks;
    }
    private ZoneId userZone(long userId) { try { return ZoneId.of(String.valueOf(userService.userView(userId).getOrDefault("timezone", "Asia/Shanghai"))); } catch (Exception ignored) { return ZoneId.of("Asia/Shanghai"); } }
    private static String maskSensitive(String value) { if (value == null) return null; String masked = PHONE_PATTERN.matcher(value).replaceAll("$1****$2"); return EMAIL_PATTERN.matcher(masked).replaceAll("$1***@$2"); }
    private void requireText(String text) { if (blank(text)) throw new BusinessException(400, "text is required"); }
    private void validateConfig(String provider, String modelName, String apiBaseUrl) {
        if (blank(provider) || blank(modelName) || blank(apiBaseUrl)) {
            throw new BusinessException(400, "provider, modelName and apiBaseUrl are required");
        }
        validateApiBaseUrl(apiBaseUrl);
    }

    private URI validateApiBaseUrl(String apiBaseUrl) {
        try {
            URI uri = URI.create(trimBaseUrl(apiBaseUrl));
            if (!"https".equalsIgnoreCase(uri.getScheme()) || blank(uri.getHost())
                    || (uri.getPort() != -1 && uri.getPort() != 443)
                    || uri.getRawUserInfo() != null || uri.getRawQuery() != null || uri.getRawFragment() != null) {
                throw new BusinessException(400, "apiBaseUrl must be an HTTPS base URL");
            }
            String host = normalizeHost(uri.getHost());
            if (!allowedApiHosts.contains(host)) {
                throw new BusinessException(400, "apiBaseUrl host is not allowed");
            }
            InetAddress[] addresses = InetAddress.getAllByName(host);
            if (addresses.length == 0 || Arrays.stream(addresses).anyMatch(AiService::isUnsafeAddress)) {
                throw new BusinessException(400, "apiBaseUrl must resolve to a public address");
            }
            return uri;
        } catch (BusinessException ex) {
            throw ex;
        } catch (Exception ex) {
            throw new BusinessException(400, "apiBaseUrl is invalid or cannot be resolved");
        }
    }

    private static Set<String> allowedApiHosts(String configuredHosts, String defaultApiBaseUrl) {
        Set<String> hosts = new LinkedHashSet<>();
        if (!blank(configuredHosts)) {
            for (String host : configuredHosts.split(",")) {
                if (!host.isBlank()) hosts.add(normalizeHost(host));
            }
        }
        if (!blank(defaultApiBaseUrl)) {
            try {
                String host = URI.create(trimBaseUrl(defaultApiBaseUrl)).getHost();
                if (!blank(host)) hosts.add(normalizeHost(host));
            } catch (Exception ignored) {
                // Invalid defaults are rejected before an outbound request is made.
            }
        }
        return Set.copyOf(hosts);
    }

    private static String normalizeHost(String host) {
        String normalized = host == null ? "" : host.trim();
        while (normalized.endsWith(".")) normalized = normalized.substring(0, normalized.length() - 1);
        return IDN.toASCII(normalized).toLowerCase(Locale.ROOT);
    }

    private static boolean isUnsafeAddress(InetAddress address) {
        if (address.isAnyLocalAddress() || address.isLoopbackAddress() || address.isLinkLocalAddress()
                || address.isSiteLocalAddress() || address.isMulticastAddress()) {
            return true;
        }
        byte[] raw = address.getAddress();
        if (raw.length == 4) {
            int first = raw[0] & 0xff;
            int second = raw[1] & 0xff;
            return first == 0
                    || (first == 100 && second >= 64 && second <= 127)
                    || (first == 198 && (second == 18 || second == 19))
                    || first >= 224;
        }
        return raw.length == 16 && ((raw[0] & 0xfe) == 0xfc);
    }
    private void addDateFilters(StringBuilder sql, List<Object> params, String dateFrom, String dateTo) { if (!blank(dateFrom)) { sql.append(" and created_at >= ?"); params.add(dateFrom + " 00:00:00"); } if (!blank(dateTo)) { sql.append(" and created_at <= ?"); params.add(dateTo + " 23:59:59"); } }
    private Map<String, Object> safeConfig(Map<String, Object> config) { Map<String, Object> out = new LinkedHashMap<>(config); out.remove("apiKey"); out.put("apiKeyMasked", maskApiKey()); return out; }
    private String maskApiKey() { if (blank(apiKey)) return ""; return apiKey.length() <= 8 ? "****" : apiKey.substring(0, 4) + "****" + apiKey.substring(apiKey.length() - 4); }
    private static String stripCodeFence(String value) { String text = value.trim(); if (text.startsWith("```")) { int firstNewline = text.indexOf('\n'); int end = text.lastIndexOf("```"); return firstNewline >= 0 && end > firstNewline ? text.substring(firstNewline + 1, end).trim() : text; } return text; }
    private static String valueOr(Map<String, Object> req, String key, String fallback) { Object value = req.get(key); return value == null ? fallback : String.valueOf(value).trim(); }
    private static boolean booleanOr(Map<String, Object> req, String key, boolean fallback) { Object value = req.get(key); return value == null ? fallback : value instanceof Boolean b ? b : Boolean.parseBoolean(String.valueOf(value)); }
    private static String stringValue(Object value) { return value == null ? "" : String.valueOf(value); }
    private static String trimBaseUrl(String value) { String url = value == null ? "" : value.trim(); while (url.endsWith("/")) url = url.substring(0, url.length() - 1); return url; }
    private static String limit(String value, int max) { if (value == null) return null; return value.length() <= max ? value : value.substring(0, max); }
    private static boolean blank(String value) { return value == null || value.isBlank(); }
    private static String nullToEmpty(String value) { return value == null ? "" : value; }
    private static String iso(java.sql.Timestamp timestamp) { return timestamp == null ? "" : OffsetDateTime.ofInstant(timestamp.toInstant(), ZoneOffset.UTC).toString(); }
}
