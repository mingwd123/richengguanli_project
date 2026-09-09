package com.dayliane.ai;

import com.dayliane.common.BusinessException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import java.io.IOException;
import java.net.URI;
import java.time.Duration;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@SpringBootTest
@ActiveProfiles("test")
class AiKeyPoolTests {
    private static final String SUCCESS_BODY = "{\"choices\":[{\"message\":{\"content\":\"{\\\"suggestion\\\":\\\"ok\\\"}\"}}]}";

    @Autowired AiService aiService;
    @Autowired JdbcTemplate jdbc;
    @MockBean AiHttpTransport transport;

    private long adminId;

    @BeforeEach
    void cleanDatabase() {
        for (String table : List.of("ai_usage_log", "ai_api_key", "ai_config", "admin_operation_log", "admin_user")) {
            jdbc.update("delete from " + table);
        }
        jdbc.update("update ai_key_pool_state set revision=0 where id=1");
        jdbc.update("insert into admin_user (username,password_hash,role,status) values ('root','hash','super_admin','active')");
        adminId = jdbc.queryForObject("select id from admin_user where username='root'", Long.class);
        aiService.updateConfig(adminId, Map.of(
                "provider", "test", "modelName", "test-model", "apiBaseUrl", "https://8.8.8.8/v1", "enabled", false),
                "127.0.0.1", "test");
        reset(transport);
    }

    @Test
    @SuppressWarnings("unchecked")
    void superAdminCanManageEncryptedKeysAndOrderWithRevisionProtection() {
        Map<String, Object> firstConfig = createKey("Primary", "first-secret-1234", true);
        Map<String, Object> secondConfig = createKey("Backup", "second-secret-5678", false);
        assertThat(firstConfig).containsEntry("keyPoolRevision", 1L);
        assertThat(secondConfig).containsEntry("keyPoolRevision", 2L);

        List<Map<String, Object>> keys = (List<Map<String, Object>>) secondConfig.get("keys");
        long firstId = id(keys.get(0));
        long secondId = id(keys.get(1));
        assertThat(keys).extracting(key -> key.get("apiKeyMasked"))
                .containsExactly("firs****1234", "seco****5678");
        assertThat(keys).allSatisfy(key -> assertThat(key).doesNotContainKeys("apiKey", "apiKeyCiphertext"));

        String retainedCiphertext = jdbc.queryForObject("select api_key_ciphertext from ai_api_key where id=?", String.class, secondId);
        Map<String, Object> updated = aiService.updateApiKey(adminId, secondId,
                Map.of("name", "Backup 2", "apiKey", "   ", "enabled", true, "remark", "standby"), "127.0.0.1", "test");
        assertThat(jdbc.queryForObject("select api_key_ciphertext from ai_api_key where id=?", String.class, secondId))
                .isEqualTo(retainedCiphertext).doesNotContain("second-secret-5678");
        assertBusinessCode(409, () -> aiService.reorderApiKeys(adminId,
                Map.of("orderedIds", List.of(secondId, firstId), "revision", 2), "127.0.0.1", "test"));

        long revision = ((Number) updated.get("keyPoolRevision")).longValue();
        Map<String, Object> reordered = aiService.reorderApiKeys(adminId,
                Map.of("orderedIds", List.of(secondId, firstId), "revision", revision), "127.0.0.1", "test");
        List<Map<String, Object>> reorderedKeys = (List<Map<String, Object>>) reordered.get("keys");
        assertThat(reorderedKeys).extracting(key -> key.get("id")).containsExactly(secondId, firstId);
        assertThat(reorderedKeys).extracting(key -> key.get("priority")).containsExactly(1, 2);

        Map<String, Object> deleted = aiService.deleteApiKey(adminId, firstId, "127.0.0.1", "test");
        assertThat((List<?>) deleted.get("keys")).hasSize(1);
        List<String> audits = jdbc.queryForList("select coalesce(before_data,'') || coalesce(after_data,'') from admin_operation_log", String.class);
        assertThat(audits).allSatisfy(value -> assertThat(value)
                .doesNotContain("first-secret-1234")
                .doesNotContain("second-secret-5678")
                .doesNotContain("apiKeyCiphertext"));
    }

    @Test
    @SuppressWarnings("unchecked")
    void keySpecificBaseUrlsAreNormalizedAndUsedDuringFailover() throws Exception {
        Map<String, Object> firstConfig = createKeyWithBaseUrl(
                "Relay", "relay-key-value", "https://1.1.1.1/openai/", true);
        List<Map<String, Object>> firstKeys = (List<Map<String, Object>>) firstConfig.get("keys");
        long relayId = id(firstKeys.get(0));
        assertThat(firstKeys.get(0)).containsEntry("apiBaseUrl", "https://1.1.1.1/openai/v1");

        Map<String, Object> secondConfig = createKey("Default route", "default-route-key", true);
        List<Map<String, Object>> keys = (List<Map<String, Object>>) secondConfig.get("keys");
        long defaultRouteId = id(keys.get(1));
        assertThat(keys.get(1)).containsEntry("apiBaseUrl", "");

        when(transport.send(eq(URI.create("https://1.1.1.1/openai/v1/chat/completions")),
                anyString(), eq("relay-key-value"), any(Duration.class)))
                .thenReturn(new AiHttpTransport.Response(429, "busy"));
        when(transport.send(eq(URI.create("https://8.8.8.8/v1/chat/completions")),
                anyString(), eq("default-route-key"), any(Duration.class)))
                .thenReturn(new AiHttpTransport.Response(200, SUCCESS_BODY));

        assertThat(aiService.test(adminId, "127.0.0.1", "test"))
                .containsEntry("keyId", defaultRouteId)
                .containsEntry("apiBaseUrl", "https://8.8.8.8/v1")
                .containsEntry("attempts", 2);
        verify(transport).send(eq(URI.create("https://1.1.1.1/openai/v1/chat/completions")),
                anyString(), eq("relay-key-value"), any(Duration.class));
        verify(transport).send(eq(URI.create("https://8.8.8.8/v1/chat/completions")),
                anyString(), eq("default-route-key"), any(Duration.class));

        Map<String, Object> updated = aiService.updateApiKey(adminId, relayId,
                Map.of("apiBaseUrl", "https://1.1.1.1/custom/v1/chat/completions"),
                "127.0.0.1", "test");
        List<Map<String, Object>> updatedKeys = (List<Map<String, Object>>) updated.get("keys");
        assertThat(updatedKeys.get(0)).containsEntry("apiBaseUrl", "https://1.1.1.1/custom/v1");
        assertThat(jdbc.queryForObject("select last_test_status from ai_api_key where id=?", String.class, relayId)).isNull();

        Map<String, Object> inherited = aiService.updateApiKey(adminId, relayId,
                Map.of("apiBaseUrl", ""), "127.0.0.1", "test");
        List<Map<String, Object>> inheritedKeys = (List<Map<String, Object>>) inherited.get("keys");
        assertThat(inheritedKeys.get(0)).containsEntry("apiBaseUrl", "");
        assertThat(jdbc.queryForObject("select api_base_url from ai_api_key where id=?", String.class, relayId)).isNull();
    }

    @Test
    void keySpecificBaseUrlsRejectUnsafeOrUnsupportedAddresses() {
        for (String apiBaseUrl : List.of(
                "http://1.1.1.1",
                "https://127.0.0.1",
                "https://10.0.0.1",
                "https://user:secret@1.1.1.1",
                "https://1.1.1.1:8443",
                "https://1.1.1.1/v1?token=secret",
                "https://1.1.1.1/v1#section")) {
            assertBusinessCode(400, () -> createKeyWithBaseUrl(
                    "Invalid relay", "invalid-relay-key", apiBaseUrl, true));
        }
        assertBusinessCode(400, () -> createKeyWithBaseUrl(
                "Long relay", "long-relay-key", "https://1.1.1.1/" + "a".repeat(500), true));
        String exactlyFiveHundredCharacters = "https://1.1.1.1/" +
                "a".repeat(500 - "https://1.1.1.1/".length());
        assertThat(exactlyFiveHundredCharacters).hasSize(500);
        assertBusinessCode(400, () -> createKeyWithBaseUrl(
                "Normalized too long", "normalized-long-key", exactlyFiveHundredCharacters, true));
        assertThat(jdbc.queryForObject("select count(*) from ai_api_key", Integer.class)).isZero();
    }

    @Test
    void customRouteFailuresFallThroughToTheNextKey() throws Exception {
        createKeyWithBaseUrl("Broken relay", "broken-route-key", "https://1.1.1.1", true);
        long fallbackId = lastKeyId(createKey("Default route", "working-route-key", true));
        when(transport.send(any(), anyString(), eq("working-route-key"), any(Duration.class)))
                .thenReturn(new AiHttpTransport.Response(200, SUCCESS_BODY));

        for (AiHttpTransport.Response response : List.of(
                new AiHttpTransport.Response(302, "redirect"),
                new AiHttpTransport.Response(400, "unsupported model"),
                new AiHttpTransport.Response(404, "wrong path"),
                new AiHttpTransport.Response(200, "{\"choices\":[]}"))) {
            reset(transport);
            when(transport.send(any(), anyString(), eq("broken-route-key"), any(Duration.class))).thenReturn(response);
            when(transport.send(any(), anyString(), eq("working-route-key"), any(Duration.class)))
                    .thenReturn(new AiHttpTransport.Response(200, SUCCESS_BODY));
            assertThat(aiService.test(adminId, "127.0.0.1", "test"))
                    .containsEntry("keyId", fallbackId)
                    .containsEntry("attempts", 2);
        }
    }

    @Test
    void keyPoolRejectsAnEleventhStoredKey() {
        assertBusinessCode(400, () -> aiService.createApiKey(adminId,
                Map.of("name", "Invalid", "apiKey", "invalid-enabled-key", "enabled", "yes"),
                "127.0.0.1", "test"));
        for (int i = 1; i <= 10; i++) createKey("Key " + i, "secret-value-" + i + "-1234", true);
        assertBusinessCode(400, () -> createKey("Key 11", "secret-value-11-1234", true));
        assertThat(jdbc.queryForObject("select count(*) from ai_api_key", Integer.class)).isEqualTo(10);
    }

    @Test
    void fullTestFallsThroughRetryableFailuresAndRecordsSafeStatuses() throws Exception {
        long firstId = firstKeyId(createKey("Primary", "first-retry-key", true));
        long secondId = lastKeyId(createKey("Backup", "second-success-key", true));
        when(transport.send(any(), anyString(), eq("first-retry-key"), any(Duration.class)))
                .thenThrow(new IOException("secret network detail"));
        when(transport.send(any(), anyString(), eq("second-success-key"), any(Duration.class)))
                .thenReturn(new AiHttpTransport.Response(200, SUCCESS_BODY));

        Map<String, Object> result = aiService.test(adminId, "127.0.0.1", "test");
        assertThat(result)
                .containsEntry("ok", true)
                .containsEntry("source", "database")
                .containsEntry("keyId", secondId)
                .containsEntry("attempts", 2);
        assertThat(jdbc.queryForMap("select last_test_status lastTestStatus,last_error lastError from ai_api_key where id=?", firstId))
                .containsEntry("lastTestStatus", "failed")
                .containsEntry("lastError", "Network error");
        assertThat(jdbc.queryForMap("select last_test_status lastTestStatus,last_error lastError from ai_api_key where id=?", secondId))
                .containsEntry("lastTestStatus", "success")
                .containsEntry("lastError", null);
        verify(transport, never()).send(any(), anyString(), eq("environment-fallback-ai-key"), any(Duration.class));
    }

    @ParameterizedTest
    @ValueSource(ints = {401, 402, 403, 408, 409, 425, 429, 500, 503, 599})
    void documentedRetryableStatusesFallThroughToTheNextKey(int status) throws Exception {
        createKey("Primary", "first-status-key", true);
        long secondId = lastKeyId(createKey("Backup", "second-status-key", true));
        when(transport.send(any(), anyString(), eq("first-status-key"), any(Duration.class)))
                .thenReturn(new AiHttpTransport.Response(status, "unavailable"));
        when(transport.send(any(), anyString(), eq("second-status-key"), any(Duration.class)))
                .thenReturn(new AiHttpTransport.Response(200, SUCCESS_BODY));

        assertThat(aiService.test(adminId, "127.0.0.1", "test"))
                .containsEntry("keyId", secondId)
                .containsEntry("attempts", 2);
    }

    @Test
    void permanentHttpAndInvalidSuccessResponsesDoNotFallThrough() throws Exception {
        createKey("Primary", "first-permanent-key", true);
        createKey("Backup", "second-unused-key", true);

        for (AiHttpTransport.Response response : List.of(
                new AiHttpTransport.Response(302, "redirect"),
                new AiHttpTransport.Response(400, "bad request"),
                new AiHttpTransport.Response(200, "{\"choices\":[]}"))) {
            reset(transport);
            when(transport.send(any(), anyString(), eq("first-permanent-key"), any(Duration.class))).thenReturn(response);
            assertBusinessCode(503, () -> aiService.test(adminId, "127.0.0.1", "test"));
            verify(transport, never()).send(any(), anyString(), eq("second-unused-key"), any(Duration.class));
            verify(transport, never()).send(any(), anyString(), eq("environment-fallback-ai-key"), any(Duration.class));
        }
    }

    @Test
    void duplicateDatabaseAndEnvironmentKeysAreRejected() {
        createKey("Primary", "unique-primary-key", true);
        assertBusinessCode(409, () -> createKey("Duplicate", "unique-primary-key", true));
        assertBusinessCode(409, () -> createKey("Same as environment", "environment-fallback-ai-key", true));
        long secondId = lastKeyId(createKey("Backup", "unique-backup-key", true));
        assertBusinessCode(409, () -> aiService.updateApiKey(adminId, secondId,
                Map.of("apiKey", "unique-primary-key"), "127.0.0.1", "test"));
        assertThat(jdbc.queryForObject("select count(*) from ai_api_key", Integer.class)).isEqualTo(2);
    }

    @Test
    void disabledKeyCanBeTestedIndividually() throws Exception {
        long keyId = firstKeyId(createKey("Disabled", "disabled-key-value", false));
        when(transport.send(any(), anyString(), eq("disabled-key-value"), any(Duration.class)))
                .thenReturn(new AiHttpTransport.Response(200, SUCCESS_BODY));

        Map<String, Object> result = aiService.testApiKey(adminId, keyId, "127.0.0.1", "test");
        assertThat(result).containsEntry("ok", true).containsEntry("keyId", keyId).containsEntry("attempts", 1);
        assertThat(jdbc.queryForObject("select last_test_status from ai_api_key where id=?", String.class, keyId))
                .isEqualTo("success");

        aiService.updateApiKey(adminId, keyId, Map.of("apiKey", "disabled-key-value"), "127.0.0.1", "test");
        assertThat(jdbc.queryForObject("select last_test_status from ai_api_key where id=?", String.class, keyId))
                .isEqualTo("success");

        aiService.updateApiKey(adminId, keyId,
                Map.of("name", "Renamed", "apiKey", "   ", "enabled", false, "remark", "metadata only"),
                "127.0.0.1", "test");
        assertThat(jdbc.queryForObject("select last_test_status from ai_api_key where id=?", String.class, keyId))
                .isEqualTo("success");

        aiService.updateApiKey(adminId, keyId, Map.of("apiKey", "replacement-key-value"), "127.0.0.1", "test");
        assertThat(jdbc.queryForObject("select last_test_status from ai_api_key where id=?", String.class, keyId))
                .isNull();
    }

    @Test
    void staleTestResultDoesNotOverwriteAReplacedKey() throws Exception {
        long keyId = firstKeyId(createKey("Rotating", "old-key-value", true));
        when(transport.send(any(), anyString(), eq("old-key-value"), any(Duration.class))).thenAnswer(invocation -> {
            aiService.updateApiKey(adminId, keyId, Map.of("apiKey", "new-key-value"), "127.0.0.1", "rotation");
            return new AiHttpTransport.Response(200, SUCCESS_BODY);
        });

        assertThat(aiService.testApiKey(adminId, keyId, "127.0.0.1", "test"))
                .containsEntry("ok", true);
        assertThat(jdbc.queryForMap("select api_key_masked apiKeyMasked,last_test_status lastTestStatus from ai_api_key where id=?", keyId))
                .containsEntry("apiKeyMasked", "new-****alue")
                .containsEntry("lastTestStatus", null);
    }

    @Test
    void staleTestResultDoesNotOverwriteAChangedBaseUrl() throws Exception {
        long keyId = firstKeyId(createKeyWithBaseUrl(
                "Moving relay", "moving-relay-key", "https://1.1.1.1/Route", true));
        when(transport.send(any(), anyString(), eq("moving-relay-key"), any(Duration.class))).thenAnswer(invocation -> {
            aiService.updateApiKey(adminId, keyId, Map.of("apiBaseUrl", "https://1.1.1.1/route"),
                    "127.0.0.1", "route change");
            return new AiHttpTransport.Response(200, SUCCESS_BODY);
        });

        assertThat(aiService.testApiKey(adminId, keyId, "127.0.0.1", "test"))
                .containsEntry("ok", true)
                .containsEntry("apiBaseUrl", "https://1.1.1.1/Route/v1");
        assertThat(jdbc.queryForMap("select api_base_url apiBaseUrl,last_test_status lastTestStatus from ai_api_key where id=?", keyId))
                .containsEntry("apiBaseUrl", "https://1.1.1.1/route/v1")
                .containsEntry("lastTestStatus", null);
    }

    @Test
    void staleInheritedTestResultDoesNotSurviveGlobalRoutingChanges() throws Exception {
        long keyId = firstKeyId(createKey("Inherited route", "inherited-route-key", true));
        when(transport.send(any(), anyString(), eq("inherited-route-key"), any(Duration.class))).thenAnswer(invocation -> {
            aiService.updateConfig(adminId, Map.of(
                            "provider", "test", "modelName", "new-model",
                            "apiBaseUrl", "https://8.8.8.8/custom", "enabled", false),
                    "127.0.0.1", "route change");
            return new AiHttpTransport.Response(200, SUCCESS_BODY);
        });

        assertThat(aiService.testApiKey(adminId, keyId, "127.0.0.1", "test"))
                .containsEntry("ok", true)
                .containsEntry("apiBaseUrl", "https://8.8.8.8/v1");
        assertThat(jdbc.queryForObject("select last_test_status from ai_api_key where id=?", String.class, keyId))
                .isNull();
    }

    private Map<String, Object> createKey(String name, String apiKey, boolean enabled) {
        return aiService.createApiKey(adminId,
                Map.of("name", name, "apiKey", apiKey, "enabled", enabled, "remark", "test key"),
                "127.0.0.1", "test");
    }

    private Map<String, Object> createKeyWithBaseUrl(String name, String apiKey, String apiBaseUrl, boolean enabled) {
        return aiService.createApiKey(adminId,
                Map.of("name", name, "apiKey", apiKey, "apiBaseUrl", apiBaseUrl,
                        "enabled", enabled, "remark", "test key"),
                "127.0.0.1", "test");
    }

    @SuppressWarnings("unchecked")
    private static long firstKeyId(Map<String, Object> config) {
        return id(((List<Map<String, Object>>) config.get("keys")).get(0));
    }

    @SuppressWarnings("unchecked")
    private static long lastKeyId(Map<String, Object> config) {
        List<Map<String, Object>> keys = (List<Map<String, Object>>) config.get("keys");
        return id(keys.get(keys.size() - 1));
    }

    private static long id(Map<String, Object> row) {
        return ((Number) row.get("id")).longValue();
    }

    private static void assertBusinessCode(int code, Runnable action) {
        assertThatThrownBy(action::run)
                .isInstanceOf(BusinessException.class)
                .extracting(ex -> ((BusinessException) ex).getCode())
                .isEqualTo(code);
    }
}
