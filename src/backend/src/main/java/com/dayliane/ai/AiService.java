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
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;

import javax.crypto.Cipher;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.net.IDN;
import java.net.InetAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.sql.PreparedStatement;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.HashSet;
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
    private static final int MAX_API_KEY_LENGTH = 4000;
    private static final int MAX_API_KEYS = 10;
    private static final int MAX_API_KEY_NAME_LENGTH = 100;
    private static final int MAX_API_BASE_URL_LENGTH = 500;
    private static final int GCM_IV_LENGTH = 12;
    private static final int GCM_TAG_LENGTH = 128;
    private static final String API_KEY_CIPHERTEXT_VERSION = "v1:";
    private static final Pattern PHONE_PATTERN = Pattern.compile("(?<!\\d)(1\\d{2})\\d{4}(\\d{4})(?!\\d)");
    private static final Pattern EMAIL_PATTERN = Pattern.compile("([A-Za-z0-9._%+-])[A-Za-z0-9._%+-]*@([A-Za-z0-9.-]+\\.[A-Za-z]{2,})");
    private final JdbcTemplate jdbc;
    private final AdminService adminService;
    private final UserService userService;
    private final ObjectMapper objectMapper;
    private final AiHttpTransport aiHttpTransport;
    private final boolean defaultEnabled;
    private final String defaultProvider;
    private final String defaultModel;
    private final String defaultApiBaseUrl;
    private final String defaultApiKey;
    private final Set<String> allowedApiHosts;
    private final SecretKey apiKeyEncryptionKey;
    private final Duration requestTimeout;
    private final Duration totalTimeout;
    private final SecureRandom secureRandom = new SecureRandom();

    public AiService(JdbcTemplate jdbc, AdminService adminService, UserService userService, ObjectMapper objectMapper,
                     AiHttpTransport aiHttpTransport,
                     @Value("${app.ai.enabled:false}") boolean defaultEnabled,
                     @Value("${app.ai.provider:}") String defaultProvider,
                      @Value("${app.ai.model:}") String defaultModel,
                      @Value("${app.ai.api-base-url:}") String defaultApiBaseUrl,
                      @Value("${app.ai.api-key:}") String apiKey,
                      @Value("${app.ai.api-key-encryption-secret:}") String apiKeyEncryptionSecret,
                      @Value("${app.jwt.secret:change-me-in-development}") String jwtSecret,
                      @Value("${app.ai.allowed-hosts:}") String allowedHosts,
                      @Value("${app.ai.request-timeout:15s}") Duration requestTimeout,
                      @Value("${app.ai.total-timeout:45s}") Duration totalTimeout) {
        this.jdbc = jdbc;
        this.adminService = adminService;
        this.userService = userService;
        this.objectMapper = objectMapper;
        this.aiHttpTransport = aiHttpTransport;
        this.defaultEnabled = defaultEnabled;
        this.defaultProvider = defaultProvider;
        this.defaultModel = defaultModel;
        this.defaultApiBaseUrl = defaultApiBaseUrl;
        this.defaultApiKey = apiKey == null ? "" : apiKey.trim();
        this.allowedApiHosts = allowedApiHosts(allowedHosts, defaultApiBaseUrl);
        this.apiKeyEncryptionKey = apiKeyEncryptionKey(blank(apiKeyEncryptionSecret) ? jwtSecret : apiKeyEncryptionSecret);
        this.requestTimeout = positiveDuration(requestTimeout, Duration.ofSeconds(15));
        this.totalTimeout = positiveDuration(totalTimeout, Duration.ofSeconds(45));
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
        List<Map<String, Object>> keys = apiKeyViews();
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("provider", config == null ? defaultProvider : config.get("provider"));
        out.put("modelName", config == null ? defaultModel : config.get("modelName"));
        out.put("apiBaseUrl", config == null ? defaultApiBaseUrl : config.get("apiBaseUrl"));
        out.put("enabled", config == null ? defaultEnabled : config.get("enabled"));
        out.put("remark", config == null ? "" : config.get("remark"));
        out.put("keys", keys);
        out.put("environmentFallback", environmentFallbackView());
        out.put("keyPoolRevision", keyPoolRevision());
        String effectiveMasked = keys.stream()
                .filter(key -> Boolean.TRUE.equals(key.get("enabled")))
                .map(key -> stringValue(key.get("apiKeyMasked")))
                .filter(value -> !blank(value))
                .findFirst()
                .orElse(maskApiKey(defaultApiKey));
        out.put("apiKeyMasked", effectiveMasked);
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
        Map<String, Object> persistedConfig = latestConfig();
        String provider = valueOr(req, "provider", String.valueOf(before.get("provider")));
        String modelName = valueOr(req, "modelName", String.valueOf(before.get("modelName")));
        String apiBaseUrl = valueOr(req, "apiBaseUrl", String.valueOf(before.get("apiBaseUrl")));
        boolean enabled = booleanOr(req, "enabled", Boolean.TRUE.equals(before.get("enabled")));
        String remark = valueOr(req, "remark", String.valueOf(before.get("remark")));
        String apiKeyCiphertext = apiKeyCiphertextForUpdate(req, persistedConfig);
        String effectiveApiKey = effectiveApiKey(apiKeyCiphertext);
        String normalizedApiBaseUrl = validateConfig(provider, modelName, apiBaseUrl);
        boolean modelChanged = !stringValue(before.get("modelName")).equals(modelName);
        boolean defaultBaseUrlChanged = !stringValue(before.get("apiBaseUrl")).equals(normalizedApiBaseUrl);
        if (hasNonBlankApiKey(req)) upsertLegacyApiKey(req, apiKeyCiphertext);
        jdbc.update("insert into ai_config (provider,model_name,api_base_url,api_key_masked,api_key_ciphertext,enabled,remark) values (?,?,?,?,?,?,?)",
                provider, modelName, normalizedApiBaseUrl, maskApiKey(effectiveApiKey), apiKeyCiphertext, enabled, limit(remark, 4000));
        if (modelChanged || defaultBaseUrlChanged) {
            String inheritedOnly = modelChanged ? "" : " where api_base_url is null";
            jdbc.update("update ai_api_key set last_test_status=null,last_tested_at=null,last_error=null" + inheritedOnly);
        }
        Map<String, Object> after = configView();
        adminService.writeAdminOperationLog(adminId, "update_ai_config", "ai_config", null, safeConfig(before), safeConfig(after), ipAddress, userAgent);
        return after;
    }

    @Transactional
    public Map<String, Object> updateEnabled(long adminId, boolean enabled, String ipAddress, String userAgent) {
        Map<String, Object> before = configView();
        Map<String, Object> persistedConfig = latestConfig();
        String provider = String.valueOf(before.get("provider"));
        String modelName = String.valueOf(before.get("modelName"));
        String apiBaseUrl = String.valueOf(before.get("apiBaseUrl"));
        String apiKeyCiphertext = persistedConfig == null ? null : stringValue(persistedConfig.get("apiKeyCiphertext"));
        String effectiveApiKey = effectiveApiKey(apiKeyCiphertext);
        String normalizedApiBaseUrl = enabled
                ? validateConfig(provider, modelName, apiBaseUrl)
                : normalizeApiBaseUrl(apiBaseUrl);
        jdbc.update("insert into ai_config (provider,model_name,api_base_url,api_key_masked,api_key_ciphertext,enabled,remark) values (?,?,?,?,?,?,?)",
                provider, modelName, normalizedApiBaseUrl, maskApiKey(effectiveApiKey), apiKeyCiphertext, enabled, limit(String.valueOf(before.get("remark")), 4000));
        Map<String, Object> after = configView();
        adminService.writeAdminOperationLog(adminId, "set_ai_enabled", "ai_config", null, safeConfig(before), safeConfig(after), ipAddress, userAgent);
        return after;
    }

    public Map<String, Object> keyPoolView() {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("keys", apiKeyViews());
        out.put("environmentFallback", environmentFallbackView());
        out.put("keyPoolRevision", keyPoolRevision());
        return out;
    }

    @Transactional
    public Map<String, Object> createApiKey(long adminId, Map<String, Object> req, String ipAddress, String userAgent) {
        lockKeyPoolRevision();
        Integer count = jdbc.queryForObject("select count(*) from ai_api_key", Integer.class);
        if (count != null && count >= MAX_API_KEYS) throw new BusinessException(400, "at most 10 AI API keys are allowed");
        String name = requiredKeyName(req.get("name"));
        String apiKey = requiredApiKey(req.get("apiKey"));
        String apiBaseUrl = optionalKeyApiBaseUrl(req.get("apiBaseUrl"));
        ensureUniqueApiKey(apiKey, null);
        boolean enabled = req.containsKey("enabled") ? booleanValue(req.get("enabled"), "enabled") : true;
        String remark = optionalRemark(req.get("remark"));
        Integer nextPriority = jdbc.queryForObject("select coalesce(max(priority),0)+1 from ai_api_key", Integer.class);
        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbc.update(connection -> {
            PreparedStatement statement = connection.prepareStatement(
                    "insert into ai_api_key (name,api_key_masked,api_key_ciphertext,api_base_url,priority,enabled,remark) values (?,?,?,?,?,?,?)",
                    new String[]{"id"});
            statement.setString(1, name);
            statement.setString(2, maskApiKey(apiKey));
            statement.setString(3, encryptApiKey(apiKey));
            statement.setString(4, nullableApiBaseUrl(apiBaseUrl));
            statement.setInt(5, nextPriority == null ? 1 : nextPriority);
            statement.setBoolean(6, enabled);
            statement.setString(7, remark);
            return statement;
        }, keyHolder);
        long keyId = keyHolder.getKey().longValue();
        incrementKeyPoolRevision();
        Map<String, Object> after = requireApiKeyView(keyId);
        adminService.writeAdminOperationLog(adminId, "create_ai_api_key", "ai_api_key", keyId, null, after, ipAddress, userAgent);
        return configView();
    }

    @Transactional
    public Map<String, Object> updateApiKey(long adminId, long keyId, Map<String, Object> req,
                                             String ipAddress, String userAgent) {
        lockKeyPoolRevision();
        Map<String, Object> current = requireApiKeyRowForUpdate(keyId);
        Map<String, Object> before = keyView(current);
        String name = req.containsKey("name") ? requiredKeyName(req.get("name")) : stringValue(current.get("name"));
        boolean enabled = req.containsKey("enabled") ? booleanValue(req.get("enabled"), "enabled") : Boolean.TRUE.equals(current.get("enabled"));
        String remark = req.containsKey("remark") ? optionalRemark(req.get("remark")) : stringValue(current.get("remark"));
        String currentApiBaseUrl = stringValue(current.get("apiBaseUrl"));
        String apiBaseUrl = req.containsKey("apiBaseUrl")
                ? optionalKeyApiBaseUrl(req.get("apiBaseUrl"))
                : currentApiBaseUrl;
        String ciphertext = stringValue(current.get("apiKeyCiphertext"));
        String masked = stringValue(current.get("apiKeyMasked"));
        boolean apiKeyChanged = false;
        if (req.containsKey("apiKey") && req.get("apiKey") != null && !blank(String.valueOf(req.get("apiKey")))) {
            String apiKey = requiredApiKey(req.get("apiKey"));
            boolean unchanged = false;
            try {
                unchanged = sameSecret(apiKey, decryptApiKey(ciphertext));
            } catch (BusinessException ignored) {
                // Supplying a replacement is also the recovery path for a damaged ciphertext.
            }
            if (!unchanged) {
                ensureUniqueApiKey(apiKey, keyId);
                ciphertext = encryptApiKey(apiKey);
                masked = maskApiKey(apiKey);
                apiKeyChanged = true;
            }
        } else if (req.containsKey("apiKey") && req.get("apiKey") != null && !(req.get("apiKey") instanceof String)) {
            throw new BusinessException(400, "apiKey is invalid");
        }
        boolean endpointChanged = !currentApiBaseUrl.equals(apiBaseUrl);
        if (apiKeyChanged || endpointChanged) {
            jdbc.update("update ai_api_key set name=?,api_key_masked=?,api_key_ciphertext=?,api_base_url=?,enabled=?,remark=?,last_test_status=null,last_tested_at=null,last_error=null where id=?",
                    name, masked, ciphertext, nullableApiBaseUrl(apiBaseUrl), enabled, remark, keyId);
        } else {
            jdbc.update("update ai_api_key set name=?,api_base_url=?,enabled=?,remark=? where id=?",
                    name, nullableApiBaseUrl(apiBaseUrl), enabled, remark, keyId);
        }
        incrementKeyPoolRevision();
        Map<String, Object> after = requireApiKeyView(keyId);
        adminService.writeAdminOperationLog(adminId, "update_ai_api_key", "ai_api_key", keyId, before, after, ipAddress, userAgent);
        return configView();
    }

    @Transactional
    public Map<String, Object> updateApiKeyEnabled(long adminId, long keyId, boolean enabled,
                                                    String ipAddress, String userAgent) {
        lockKeyPoolRevision();
        Map<String, Object> current = requireApiKeyRowForUpdate(keyId);
        Map<String, Object> before = keyView(current);
        jdbc.update("update ai_api_key set enabled=? where id=?", enabled, keyId);
        incrementKeyPoolRevision();
        Map<String, Object> after = requireApiKeyView(keyId);
        adminService.writeAdminOperationLog(adminId, "set_ai_api_key_enabled", "ai_api_key", keyId, before, after, ipAddress, userAgent);
        return configView();
    }

    @Transactional
    public Map<String, Object> deleteApiKey(long adminId, long keyId, String ipAddress, String userAgent) {
        lockKeyPoolRevision();
        Map<String, Object> current = requireApiKeyRowForUpdate(keyId);
        Map<String, Object> before = keyView(current);
        jdbc.update("delete from ai_api_key where id=?", keyId);
        normalizeKeyPriorities();
        incrementKeyPoolRevision();
        adminService.writeAdminOperationLog(adminId, "delete_ai_api_key", "ai_api_key", keyId, before, null, ipAddress, userAgent);
        return configView();
    }

    @Transactional
    public Map<String, Object> reorderApiKeys(long adminId, Map<String, Object> req, String ipAddress, String userAgent) {
        long currentRevision = lockKeyPoolRevision();
        long requestedRevision = requiredLong(req.get("revision"), "revision");
        if (requestedRevision != currentRevision) throw new BusinessException(409, "AI API key order has changed; refresh and try again");
        List<Long> orderedIds = requiredOrderedIds(req.get("orderedIds"));
        List<Long> existingIds = jdbc.queryForList("select id from ai_api_key order by priority,id for update", Long.class);
        if (orderedIds.size() != existingIds.size() || !new HashSet<>(orderedIds).equals(new HashSet<>(existingIds))) {
            throw new BusinessException(400, "orderedIds must contain every AI API key exactly once");
        }
        if (new HashSet<>(orderedIds).size() != orderedIds.size()) {
            throw new BusinessException(400, "orderedIds contains duplicate values");
        }
        for (int i = 0; i < orderedIds.size(); i++) {
            jdbc.update("update ai_api_key set priority=? where id=?", i + 1, orderedIds.get(i));
        }
        incrementKeyPoolRevision();
        adminService.writeAdminOperationLog(adminId, "reorder_ai_api_keys", "ai_key_pool", null,
                Map.of("orderedIds", existingIds, "revision", currentRevision),
                Map.of("orderedIds", orderedIds, "revision", currentRevision + 1), ipAddress, userAgent);
        return configView();
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

    public Map<String, Object> test(long adminId, String ipAddress, String userAgent) {
        try {
            CallResult result = callWithCandidates("Reply with JSON only: {\"suggestion\":\"ok\"}", activeApiKeyCandidates(), false, true);
            Map<String, Object> response = testResult(result);
            adminService.writeAdminOperationLog(adminId, "test_ai_key_pool", "ai_key_pool", null, null, safeTestResult(response), ipAddress, userAgent);
            return response;
        } catch (BusinessException ex) {
            adminService.writeAdminOperationLog(adminId, "test_ai_key_pool", "ai_key_pool", null, null,
                    Map.of("ok", false, "error", "AI service is unavailable"), ipAddress, userAgent);
            throw ex;
        }
    }

    public Map<String, Object> testApiKey(long adminId, long keyId, String ipAddress, String userAgent) {
        KeyCandidate candidate = requireApiKeyCandidate(keyId);
        try {
            CallResult result = callWithCandidates("Reply with JSON only: {\"suggestion\":\"ok\"}", List.of(candidate), false, true);
            Map<String, Object> response = testResult(result);
            adminService.writeAdminOperationLog(adminId, "test_ai_api_key", "ai_api_key", keyId, null, safeTestResult(response), ipAddress, userAgent);
            return response;
        } catch (BusinessException ex) {
            adminService.writeAdminOperationLog(adminId, "test_ai_api_key", "ai_api_key", keyId, null,
                    Map.of("ok", false, "error", "AI service is unavailable"), ipAddress, userAgent);
            throw ex;
        }
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
        return callWithCandidates(prompt, activeApiKeyCandidates(), true, false).content();
    }

    private CallResult callWithCandidates(String prompt, List<KeyCandidate> candidates, boolean requireEnabled, boolean recordTestStatus) {
        Map<String, Object> config = effectiveConfig();
        if (requireEnabled && !Boolean.TRUE.equals(config.get("enabled"))) throw new BusinessException(400, "AI service is disabled");
        String defaultBaseUrl = String.valueOf(config.get("apiBaseUrl"));
        String model = String.valueOf(config.get("modelName"));
        if (candidates.isEmpty() || blank(model)) throw new BusinessException(400, "AI configuration is incomplete");
        try {
            String body = objectMapper.writeValueAsString(Map.of("model", model, "messages", List.of(Map.of("role", "system", "content", "You are a helpful scheduling assistant. Follow the requested JSON schema exactly. All user-facing text in the JSON response must be written in Chinese."), Map.of("role", "user", "content", prompt)), "temperature", 0.2));
            long deadline = System.nanoTime() + totalTimeout.toNanos();
            int attempts = 0;
            for (KeyCandidate candidate : candidates) {
                Duration remaining = remainingDuration(deadline);
                if (remaining == null) break;
                Duration attemptTimeout = remaining.compareTo(requestTimeout) < 0 ? remaining : requestTimeout;
                attempts++;
                String candidateBaseUrl = blank(candidate.apiBaseUrl()) ? defaultBaseUrl : candidate.apiBaseUrl();
                if (blank(candidateBaseUrl)) {
                    recordKeyTest(candidate, false, "Base URL is missing", recordTestStatus);
                    continue;
                }
                URI endpoint;
                try {
                    endpoint = chatCompletionsEndpoint(candidateBaseUrl, !blank(candidate.apiBaseUrl()));
                } catch (BusinessException ex) {
                    recordKeyTest(candidate, false, "Invalid Base URL", recordTestStatus);
                    continue;
                }
                AiHttpTransport.Response response;
                try {
                    response = aiHttpTransport.send(endpoint, body, candidate.apiKey(), attemptTimeout);
                } catch (java.net.http.HttpTimeoutException ex) {
                    recordKeyTest(candidate, false, "Request timed out", recordTestStatus);
                    continue;
                } catch (java.io.IOException ex) {
                    recordKeyTest(candidate, false, "Network error", recordTestStatus);
                    continue;
                } catch (InterruptedException ex) {
                    Thread.currentThread().interrupt();
                    recordKeyTest(candidate, false, "Request interrupted", recordTestStatus);
                    throw new BusinessException(503, "AI service is unavailable");
                }
                int status = response.statusCode();
                if (isRetryableStatus(status)) {
                    recordKeyTest(candidate, false, "HTTP " + status, recordTestStatus);
                    continue;
                }
                if (status < 200 || status >= 300) {
                    recordKeyTest(candidate, false, "HTTP " + status, recordTestStatus);
                    if (!blank(candidate.apiBaseUrl())) continue;
                    throw new BusinessException(503, "AI service is unavailable");
                }
                JsonNode content;
                try {
                    content = objectMapper.readTree(response.body()).path("choices").path(0).path("message").path("content");
                } catch (Exception ex) {
                    recordKeyTest(candidate, false, "Invalid response", recordTestStatus);
                    if (!blank(candidate.apiBaseUrl())) continue;
                    throw new BusinessException(503, "AI service returned an invalid response");
                }
                if (!content.isTextual() || content.asText().isBlank()) {
                    recordKeyTest(candidate, false, "Invalid response", recordTestStatus);
                    if (!blank(candidate.apiBaseUrl())) continue;
                    throw new BusinessException(503, "AI service returned an invalid response");
                }
                recordKeyTest(candidate, true, null, recordTestStatus);
                return new CallResult(content.asText(), candidate, attempts, candidateBaseUrl);
            }
            throw new BusinessException(503, "AI service is unavailable");
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
        Map<String, Object> effective = config == null ? new LinkedHashMap<>() : new LinkedHashMap<>(config);
        if (config == null) {
            effective.put("provider", defaultProvider);
            effective.put("modelName", defaultModel);
            effective.put("apiBaseUrl", defaultApiBaseUrl);
            effective.put("enabled", defaultEnabled);
        }
        List<KeyCandidate> candidates = activeApiKeyCandidates();
        effective.put("apiKey", candidates.isEmpty() ? "" : candidates.get(0).apiKey());
        return effective;
    }

    private Map<String, Object> latestConfig() {
        try {
            return jdbc.queryForObject("select id,provider,model_name modelName,api_base_url apiBaseUrl,api_key_ciphertext apiKeyCiphertext,enabled,remark,created_at createdAt,updated_at updatedAt from ai_config order by id desc limit 1", (rs, i) -> {
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("id", rs.getLong("id")); row.put("provider", rs.getString("provider")); row.put("modelName", rs.getString("modelName")); row.put("apiBaseUrl", rs.getString("apiBaseUrl")); row.put("apiKeyCiphertext", rs.getString("apiKeyCiphertext")); row.put("enabled", rs.getBoolean("enabled")); row.put("remark", rs.getString("remark")); row.put("createdAt", iso(rs.getTimestamp("createdAt"))); row.put("updatedAt", iso(rs.getTimestamp("updatedAt"))); return row;
            });
        } catch (EmptyResultDataAccessException ex) { return null; }
    }

    private List<Map<String, Object>> apiKeyViews() {
        return apiKeyRows(false).stream().map(this::keyView).toList();
    }

    private List<Map<String, Object>> apiKeyRows(boolean enabledOnly) {
        return apiKeyRows(enabledOnly, false);
    }

    private List<Map<String, Object>> apiKeyRows(boolean enabledOnly, boolean forUpdate) {
        String sql = "select id,name,api_key_masked apiKeyMasked,api_key_ciphertext apiKeyCiphertext,api_base_url apiBaseUrl,priority,enabled,remark," +
                "last_test_status lastTestStatus,last_tested_at lastTestedAt,last_error lastError,created_at createdAt,updated_at updatedAt " +
                "from ai_api_key" + (enabledOnly ? " where enabled=true" : "") + " order by priority,id" + (forUpdate ? " for update" : "");
        return jdbc.query(sql, (rs, i) -> {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("id", rs.getLong("id"));
            row.put("name", rs.getString("name"));
            row.put("apiKeyMasked", rs.getString("apiKeyMasked"));
            row.put("apiKeyCiphertext", rs.getString("apiKeyCiphertext"));
            row.put("apiBaseUrl", nullToEmpty(rs.getString("apiBaseUrl")));
            row.put("priority", rs.getInt("priority"));
            row.put("enabled", rs.getBoolean("enabled"));
            row.put("remark", rs.getString("remark"));
            row.put("lastTestStatus", rs.getString("lastTestStatus"));
            row.put("lastTestedAt", iso(rs.getTimestamp("lastTestedAt")));
            row.put("lastError", rs.getString("lastError"));
            row.put("createdAt", iso(rs.getTimestamp("createdAt")));
            row.put("updatedAt", iso(rs.getTimestamp("updatedAt")));
            return row;
        });
    }

    private Map<String, Object> keyView(Map<String, Object> row) {
        Map<String, Object> view = new LinkedHashMap<>(row);
        view.remove("apiKeyCiphertext");
        return view;
    }

    private Map<String, Object> requireApiKeyView(long keyId) {
        return keyView(requireApiKeyRow(keyId, false));
    }

    private Map<String, Object> requireApiKeyRowForUpdate(long keyId) {
        return requireApiKeyRow(keyId, true);
    }

    private Map<String, Object> requireApiKeyRow(long keyId, boolean forUpdate) {
        try {
            String suffix = forUpdate ? " for update" : "";
            return jdbc.queryForObject("select id,name,api_key_masked apiKeyMasked,api_key_ciphertext apiKeyCiphertext,api_base_url apiBaseUrl,priority,enabled,remark," +
                    "last_test_status lastTestStatus,last_tested_at lastTestedAt,last_error lastError,created_at createdAt,updated_at updatedAt " +
                    "from ai_api_key where id=?" + suffix, (rs, i) -> {
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("id", rs.getLong("id"));
                row.put("name", rs.getString("name"));
                row.put("apiKeyMasked", rs.getString("apiKeyMasked"));
                row.put("apiKeyCiphertext", rs.getString("apiKeyCiphertext"));
                row.put("apiBaseUrl", nullToEmpty(rs.getString("apiBaseUrl")));
                row.put("priority", rs.getInt("priority"));
                row.put("enabled", rs.getBoolean("enabled"));
                row.put("remark", rs.getString("remark"));
                row.put("lastTestStatus", rs.getString("lastTestStatus"));
                row.put("lastTestedAt", iso(rs.getTimestamp("lastTestedAt")));
                row.put("lastError", rs.getString("lastError"));
                row.put("createdAt", iso(rs.getTimestamp("createdAt")));
                row.put("updatedAt", iso(rs.getTimestamp("updatedAt")));
                return row;
            }, keyId);
        } catch (EmptyResultDataAccessException ex) {
            throw new BusinessException(404, "AI API key not found");
        }
    }

    private List<KeyCandidate> activeApiKeyCandidates() {
        List<KeyCandidate> candidates = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        long configVersion = latestConfigVersion();
        long poolRevision = keyPoolRevision();
        for (Map<String, Object> row : apiKeyRows(true)) {
            try {
                String apiKey = decryptApiKey(stringValue(row.get("apiKeyCiphertext")));
                if (!blank(apiKey) && seen.add(apiKey)) {
                    candidates.add(new KeyCandidate(((Number) row.get("id")).longValue(), stringValue(row.get("name")), apiKey,
                            stringValue(row.get("apiBaseUrl")), "database", stringValue(row.get("apiKeyCiphertext")), configVersion, poolRevision));
                }
            } catch (BusinessException ignored) {
                // A damaged stored key must not prevent later keys or the environment fallback from serving requests.
            }
        }
        if (!blank(defaultApiKey) && seen.add(defaultApiKey)) {
            candidates.add(new KeyCandidate(null, "Environment fallback", defaultApiKey, "", "environment", null, configVersion, poolRevision));
        }
        return candidates;
    }

    private KeyCandidate requireApiKeyCandidate(long keyId) {
        long configVersion = latestConfigVersion();
        long poolRevision = keyPoolRevision();
        Map<String, Object> row = requireApiKeyRow(keyId, false);
        String ciphertext = stringValue(row.get("apiKeyCiphertext"));
        return new KeyCandidate(keyId, stringValue(row.get("name")), decryptApiKey(ciphertext),
                stringValue(row.get("apiBaseUrl")), "database", ciphertext, configVersion, poolRevision);
    }

    private Map<String, Object> environmentFallbackView() {
        Map<String, Object> fallback = new LinkedHashMap<>();
        fallback.put("configured", !blank(defaultApiKey));
        fallback.put("masked", maskApiKey(defaultApiKey));
        fallback.put("apiKeyMasked", maskApiKey(defaultApiKey));
        fallback.put("source", "environment");
        return fallback;
    }

    private long keyPoolRevision() {
        Long revision = jdbc.queryForObject("select revision from ai_key_pool_state where id=1", Long.class);
        return revision == null ? 0 : revision;
    }

    private long latestConfigVersion() {
        Long version = jdbc.queryForObject("select coalesce(max(id),0) from ai_config", Long.class);
        return version == null ? 0 : version;
    }

    private long lockKeyPoolRevision() {
        try {
            Long revision = jdbc.queryForObject("select revision from ai_key_pool_state where id=1 for update", Long.class);
            return revision == null ? 0 : revision;
        } catch (EmptyResultDataAccessException ex) {
            jdbc.update("insert into ai_key_pool_state (id,revision) values (1,0)");
            return 0;
        }
    }

    private void incrementKeyPoolRevision() {
        jdbc.update("update ai_key_pool_state set revision=revision+1 where id=1");
    }

    private void normalizeKeyPriorities() {
        List<Long> ids = jdbc.queryForList("select id from ai_api_key order by priority,id", Long.class);
        for (int i = 0; i < ids.size(); i++) jdbc.update("update ai_api_key set priority=? where id=?", i + 1, ids.get(i));
    }

    private void upsertLegacyApiKey(Map<String, Object> req, String ciphertext) {
        String apiKey = decryptApiKey(ciphertext);
        lockKeyPoolRevision();
        List<Long> ids = jdbc.queryForList("select id from ai_api_key order by priority,id limit 1 for update", Long.class);
        ensureUniqueApiKey(apiKey, ids.isEmpty() ? null : ids.get(0));
        if (ids.isEmpty()) {
            jdbc.update("insert into ai_api_key (name,api_key_masked,api_key_ciphertext,priority,enabled,remark) values (?,?,?,?,?,?)",
                    "Primary key", maskApiKey(apiKey), ciphertext, 1, true, optionalRemark(req.get("remark")));
        } else {
            jdbc.update("update ai_api_key set api_key_masked=?,api_key_ciphertext=?,last_test_status=null,last_tested_at=null,last_error=null where id=?",
                    maskApiKey(apiKey), ciphertext, ids.get(0));
        }
        incrementKeyPoolRevision();
    }

    private void ensureUniqueApiKey(String apiKey, Long excludedKeyId) {
        if (!blank(defaultApiKey) && sameSecret(apiKey, defaultApiKey)) {
            throw new BusinessException(409, "AI API key duplicates the environment fallback");
        }
        for (Map<String, Object> row : apiKeyRows(false, true)) {
            long rowId = ((Number) row.get("id")).longValue();
            if (excludedKeyId != null && rowId == excludedKeyId) continue;
            try {
                if (sameSecret(apiKey, decryptApiKey(stringValue(row.get("apiKeyCiphertext"))))) {
                    throw new BusinessException(409, "AI API key already exists");
                }
            } catch (BusinessException ex) {
                if (ex.getCode() == 409) throw ex;
                // A separate damaged row is ignored here and remains visible for an administrator to replace.
            }
        }
    }

    private static boolean sameSecret(String left, String right) {
        return MessageDigest.isEqual(left.getBytes(StandardCharsets.UTF_8), right.getBytes(StandardCharsets.UTF_8));
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
    private String validateConfig(String provider, String modelName, String apiBaseUrl) {
        if (blank(provider) || blank(modelName) || blank(apiBaseUrl)) {
            throw new BusinessException(400, "provider, modelName and apiBaseUrl are required");
        }
        return validateApiBaseUrl(apiBaseUrl, true).toString();
    }

    private URI validateApiBaseUrl(String apiBaseUrl) {
        return validateApiBaseUrl(apiBaseUrl, true);
    }

    private URI validateApiBaseUrl(String apiBaseUrl, boolean requireAllowedHost) {
        try {
            URI uri = URI.create(normalizeApiBaseUrl(apiBaseUrl));
            if (!"https".equalsIgnoreCase(uri.getScheme()) || blank(uri.getHost())
                    || (uri.getPort() != -1 && uri.getPort() != 443)
                    || uri.getRawUserInfo() != null || uri.getRawQuery() != null || uri.getRawFragment() != null) {
                throw new BusinessException(400, "apiBaseUrl must be an HTTPS base URL");
            }
            String host = normalizeHost(uri.getHost());
            if (requireAllowedHost && !allowedApiHosts.contains(host)) {
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

    private URI chatCompletionsEndpoint(String apiBaseUrl, boolean keyOverride) {
        URI validatedBaseUrl = validateApiBaseUrl(apiBaseUrl, !keyOverride);
        return URI.create(trimBaseUrl(validatedBaseUrl.toString()) + "/chat/completions");
    }

    private String optionalKeyApiBaseUrl(Object value) {
        if (value == null) return "";
        if (!(value instanceof String text)) throw new BusinessException(400, "apiBaseUrl is invalid");
        if (blank(text)) return "";
        return validateApiBaseUrl(text, false).toString();
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

    private Map<String, Object> testResult(CallResult result) {
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("ok", true);
        response.put("source", result.candidate().source());
        response.put("keyId", result.candidate().id());
        response.put("keyName", result.candidate().name());
        response.put("apiBaseUrl", result.apiBaseUrl());
        response.put("attempts", result.attempts());
        response.put("rawText", limit(result.content(), MAX_LOG_LENGTH));
        return response;
    }

    private Map<String, Object> safeTestResult(Map<String, Object> result) {
        Map<String, Object> safe = new LinkedHashMap<>(result);
        safe.remove("rawText");
        return safe;
    }

    private void recordKeyTest(KeyCandidate candidate, boolean success, String error, boolean enabled) {
        if (!enabled || candidate.id() == null) return;
        jdbc.update("update ai_api_key set last_test_status=?,last_tested_at=utc_timestamp(),last_error=? " +
                        "where id=? and api_key_ciphertext=? and coalesce(api_base_url,'')=? " +
                        "and (select revision from ai_key_pool_state where id=1)=? " +
                        "and (select coalesce(max(id),0) from ai_config)=?",
                success ? "success" : "failed", success ? null : limit(error, 500),
                candidate.id(), candidate.ciphertext(), candidate.apiBaseUrl(),
                candidate.poolRevision(), candidate.configVersion());
    }

    private static boolean isRetryableStatus(int status) {
        return status >= 500 && status <= 599
                || status == 401 || status == 402 || status == 403 || status == 408
                || status == 409 || status == 425 || status == 429;
    }

    private static Duration remainingDuration(long deadlineNanos) {
        long remaining = deadlineNanos - System.nanoTime();
        return remaining <= 0 ? null : Duration.ofNanos(remaining);
    }

    private static Duration positiveDuration(Duration value, Duration fallback) {
        if (value == null || value.isZero() || value.isNegative()) return fallback;
        Duration maximum = Duration.ofMinutes(5);
        return value.compareTo(maximum) > 0 ? maximum : value;
    }

    private static String requiredKeyName(Object value) {
        if (!(value instanceof String name) || blank(name)) throw new BusinessException(400, "name is required");
        String normalized = name.trim();
        if (normalized.length() > MAX_API_KEY_NAME_LENGTH) throw new BusinessException(400, "name is too long");
        return normalized;
    }

    private static String requiredApiKey(Object value) {
        if (!(value instanceof String apiKey) || blank(apiKey)) throw new BusinessException(400, "apiKey is required");
        String normalized = apiKey.trim();
        if (normalized.length() > MAX_API_KEY_LENGTH) throw new BusinessException(400, "apiKey is too long");
        return normalized;
    }

    private static String optionalRemark(Object value) {
        if (value == null) return "";
        if (!(value instanceof String remark)) throw new BusinessException(400, "remark is invalid");
        return limit(remark.trim(), 4000);
    }

    private static boolean booleanValue(Object value, String field) {
        if (value instanceof Boolean bool) return bool;
        if (value instanceof String text && ("true".equalsIgnoreCase(text) || "false".equalsIgnoreCase(text))) {
            return Boolean.parseBoolean(text);
        }
        throw new BusinessException(400, field + " is invalid");
    }

    private static long requiredLong(Object value, String field) {
        try {
            long result = value instanceof Number number ? number.longValue() : Long.parseLong(String.valueOf(value));
            if (result < 0) throw new NumberFormatException();
            return result;
        } catch (Exception ex) {
            throw new BusinessException(400, field + " is invalid");
        }
    }

    private static List<Long> requiredOrderedIds(Object value) {
        if (!(value instanceof List<?> values)) throw new BusinessException(400, "orderedIds is required");
        List<Long> ids = new ArrayList<>();
        for (Object item : values) {
            long id = requiredLong(item, "orderedIds");
            if (id <= 0) throw new BusinessException(400, "orderedIds is invalid");
            ids.add(id);
        }
        return ids;
    }

    private static boolean hasNonBlankApiKey(Map<String, Object> req) {
        return req.containsKey("apiKey") && req.get("apiKey") instanceof String value && !blank(value);
    }

    private void addDateFilters(StringBuilder sql, List<Object> params, String dateFrom, String dateTo) { if (!blank(dateFrom)) { sql.append(" and created_at >= ?"); params.add(dateFrom + " 00:00:00"); } if (!blank(dateTo)) { sql.append(" and created_at <= ?"); params.add(dateTo + " 23:59:59"); } }
    private String apiKeyCiphertextForUpdate(Map<String, Object> req, Map<String, Object> persistedConfig) {
        String currentCiphertext = persistedConfig == null ? null : stringValue(persistedConfig.get("apiKeyCiphertext"));
        if (!req.containsKey("apiKey") || req.get("apiKey") == null) return currentCiphertext;
        if (!(req.get("apiKey") instanceof String value)) throw new BusinessException(400, "apiKey is invalid");
        String requestedApiKey = value.trim();
        if (blank(requestedApiKey)) return currentCiphertext;
        if (requestedApiKey.length() > MAX_API_KEY_LENGTH) throw new BusinessException(400, "apiKey is too long");
        return encryptApiKey(requestedApiKey);
    }

    private String effectiveApiKey(Map<String, Object> config) {
        return effectiveApiKey(config == null ? null : stringValue(config.get("apiKeyCiphertext")));
    }

    private String effectiveApiKey(String apiKeyCiphertext) {
        String configuredApiKey = blank(apiKeyCiphertext) ? "" : decryptApiKey(apiKeyCiphertext);
        return blank(configuredApiKey) ? defaultApiKey : configuredApiKey;
    }

    private String encryptApiKey(String apiKey) {
        try {
            byte[] iv = new byte[GCM_IV_LENGTH];
            secureRandom.nextBytes(iv);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, apiKeyEncryptionKey, new GCMParameterSpec(GCM_TAG_LENGTH, iv));
            byte[] encrypted = cipher.doFinal(apiKey.getBytes(StandardCharsets.UTF_8));
            byte[] payload = new byte[iv.length + encrypted.length];
            System.arraycopy(iv, 0, payload, 0, iv.length);
            System.arraycopy(encrypted, 0, payload, iv.length, encrypted.length);
            return API_KEY_CIPHERTEXT_VERSION + Base64.getUrlEncoder().withoutPadding().encodeToString(payload);
        } catch (Exception ex) {
            throw new IllegalStateException("Unable to encrypt AI API key", ex);
        }
    }

    private String decryptApiKey(String ciphertext) {
        try {
            if (!ciphertext.startsWith(API_KEY_CIPHERTEXT_VERSION)) throw new IllegalArgumentException("Unsupported AI API key encryption format");
            byte[] payload = Base64.getUrlDecoder().decode(ciphertext.substring(API_KEY_CIPHERTEXT_VERSION.length()));
            if (payload.length <= GCM_IV_LENGTH) throw new IllegalArgumentException("Invalid AI API key ciphertext");
            byte[] iv = Arrays.copyOfRange(payload, 0, GCM_IV_LENGTH);
            byte[] encrypted = Arrays.copyOfRange(payload, GCM_IV_LENGTH, payload.length);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, apiKeyEncryptionKey, new GCMParameterSpec(GCM_TAG_LENGTH, iv));
            return new String(cipher.doFinal(encrypted), StandardCharsets.UTF_8);
        } catch (Exception ex) {
            throw new BusinessException(500, "AI API key configuration cannot be read");
        }
    }

    private static SecretKey apiKeyEncryptionKey(String secret) {
        try {
            byte[] material = MessageDigest.getInstance("SHA-256").digest(secret.getBytes(StandardCharsets.UTF_8));
            return new SecretKeySpec(material, "AES");
        } catch (Exception ex) {
            throw new IllegalStateException("Unable to initialize AI API key encryption", ex);
        }
    }

    private Map<String, Object> safeConfig(Map<String, Object> config) {
        Map<String, Object> out = new LinkedHashMap<>(config);
        Object apiKey = out.remove("apiKey");
        out.remove("apiKeyCiphertext");
        if (apiKey != null) out.put("apiKeyMasked", maskApiKey(String.valueOf(apiKey)));
        else out.putIfAbsent("apiKeyMasked", "");
        return out;
    }

    private static String maskApiKey(String apiKey) {
        if (blank(apiKey)) return "";
        return apiKey.length() <= 8 ? "****" : apiKey.substring(0, 4) + "****" + apiKey.substring(apiKey.length() - 4);
    }
    private static String stripCodeFence(String value) { String text = value.trim(); if (text.startsWith("```")) { int firstNewline = text.indexOf('\n'); int end = text.lastIndexOf("```"); return firstNewline >= 0 && end > firstNewline ? text.substring(firstNewline + 1, end).trim() : text; } return text; }
    private static String valueOr(Map<String, Object> req, String key, String fallback) { Object value = req.get(key); return value == null ? fallback : String.valueOf(value).trim(); }
    private static boolean booleanOr(Map<String, Object> req, String key, boolean fallback) { Object value = req.get(key); return value == null ? fallback : value instanceof Boolean b ? b : Boolean.parseBoolean(String.valueOf(value)); }
    private static String stringValue(Object value) { return value == null ? "" : String.valueOf(value); }
    private static String trimBaseUrl(String value) { String url = value == null ? "" : value.trim(); while (url.endsWith("/")) url = url.substring(0, url.length() - 1); return url; }
    private static String nullableApiBaseUrl(String value) { return blank(value) ? null : value; }
    private static String normalizeApiBaseUrl(String value) {
        String trimmed = trimBaseUrl(value);
        if (blank(trimmed)) return "";
        if (trimmed.length() > MAX_API_BASE_URL_LENGTH) {
            throw new BusinessException(400, "apiBaseUrl must be at most 500 characters");
        }
        try {
            URI uri = URI.create(trimmed);
            if (!"https".equalsIgnoreCase(uri.getScheme()) || blank(uri.getHost())
                    || (uri.getPort() != -1 && uri.getPort() != 443)
                    || uri.getRawUserInfo() != null || uri.getRawQuery() != null || uri.getRawFragment() != null) {
                throw new BusinessException(400, "apiBaseUrl must be an HTTPS base URL");
            }
            String path = uri.getPath() == null ? "" : uri.getPath();
            while (path.endsWith("/")) path = path.substring(0, path.length() - 1);
            if (path.endsWith("/chat/completions")) path = path.substring(0, path.length() - "/chat/completions".length());
            while (path.endsWith("/")) path = path.substring(0, path.length() - 1);
            if (path.isBlank()) path = "/v1";
            else if (!path.equals("/v1") && !path.endsWith("/v1")) path += "/v1";
            String host = uri.getHost();
            String canonicalHost = host != null && !host.contains(":") ? normalizeHost(host) : host;
            String normalized = new URI(uri.getScheme() == null ? null : uri.getScheme().toLowerCase(Locale.ROOT),
                    null, canonicalHost, uri.getPort(), path, null, null).toString();
            if (normalized.length() > MAX_API_BASE_URL_LENGTH) {
                throw new BusinessException(400, "apiBaseUrl must be at most 500 characters");
            }
            return normalized;
        } catch (BusinessException ex) {
            throw ex;
        } catch (Exception ex) {
            throw new BusinessException(400, "apiBaseUrl is invalid or cannot be resolved");
        }
    }
    private static String limit(String value, int max) { if (value == null) return null; return value.length() <= max ? value : value.substring(0, max); }
    private static boolean blank(String value) { return value == null || value.isBlank(); }
    private static String nullToEmpty(String value) { return value == null ? "" : value; }
    private static String iso(java.sql.Timestamp timestamp) { return timestamp == null ? "" : OffsetDateTime.ofInstant(timestamp.toInstant(), ZoneOffset.UTC).toString(); }

    private record KeyCandidate(Long id, String name, String apiKey, String apiBaseUrl, String source,
                                String ciphertext, long configVersion, long poolRevision) {}
    private record CallResult(String content, KeyCandidate candidate, int attempts, String apiBaseUrl) {}
}
