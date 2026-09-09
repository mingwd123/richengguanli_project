package com.dayliane.ai;

import com.dayliane.auth.AuthService;
import com.dayliane.common.BusinessException;
import com.dayliane.user.UserService;
import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import java.time.Duration;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.when;

@SpringBootTest
@ActiveProfiles("test")
class AiRecordPreferenceTests {
    private static final String SUCCESS_BODY = "{\"choices\":[{\"message\":{\"content\":\"{\\\"title\\\":\\\"学习\\\",\\\"timeType\\\":\\\"point_event\\\"}\"}}]}";

    @Autowired AuthService authService;
    @Autowired AiController aiController;
    @Autowired AiService aiService;
    @Autowired UserService userService;
    @Autowired JdbcTemplate jdbc;
    @MockBean AiHttpTransport transport;

    @BeforeEach
    void cleanAiData() {
        jdbc.update("delete from ai_usage_log");
        jdbc.update("delete from ai_api_key");
        jdbc.update("delete from ai_config");
        jdbc.update("update ai_key_pool_state set revision=0 where id=1");
        reset(transport);
    }

    @Test
    void preferenceDefaultsToEnabledAndPersistsForServiceReads() {
        long userId = register("15100041001");

        assertThat(userService.userView(userId)).containsEntry("aiRecordEnabled", true);
        assertThat(aiService.aiRecordEnabled(userId)).isTrue();
        assertThat(jdbc.queryForObject("select ai_record_enabled from `user` where id=?", Boolean.class, userId)).isTrue();

        userService.updateUserProfile(userId, Map.of("aiRecordEnabled", false));

        assertThat(userService.userView(userId)).containsEntry("aiRecordEnabled", false);
        assertThat(aiService.aiRecordEnabled(userId)).isFalse();
        assertThat(jdbc.queryForObject("select ai_record_enabled from `user` where id=?", Boolean.class, userId)).isFalse();

        userService.updateUserProfile(userId, Map.of("aiRecordEnabled", true));
        assertThat(aiService.aiRecordEnabled(userId)).isTrue();
    }

    @Test
    void invalidPreferenceDoesNotOverwriteStoredValue() {
        long userId = register("15100041002");
        userService.updateUserProfile(userId, Map.of("aiRecordEnabled", false));

        assertThatThrownBy(() -> userService.updateUserProfile(userId, Map.of("aiRecordEnabled", "yes")))
                .isInstanceOfSatisfying(BusinessException.class,
                        ex -> assertThat(ex.getCode()).isEqualTo(400));
        assertThat(aiService.aiRecordEnabled(userId)).isFalse();
    }

    @Test
    void arrangeControllerUsesStoredPreferenceInsteadOfClientFlag() {
        long userId = register("15100041003");
        HttpServletRequest request = authorizedRequest(userId);

        userService.updateUserProfile(userId, Map.of("aiRecordEnabled", false));
        assertThat(aiController.arrangeSchedules(request, Map.of("recordUsage", true)).data())
                .containsEntry("disabled", true);

        userService.updateUserProfile(userId, Map.of("aiRecordEnabled", true));
        assertThat(aiController.arrangeSchedules(request, Map.of("recordUsage", false)).data())
                .containsEntry("disabled", false);
    }

    @Test
    void parseControllerUsesStoredPreferenceWhenSavingAiContent() throws Exception {
        long userId = register("15100041004");
        HttpServletRequest request = authorizedRequest(userId);
        jdbc.update("insert into ai_config (provider,model_name,api_base_url,enabled) values (?,?,?,true)",
                "test", "test-model", "https://8.8.8.8/v1");
        when(transport.send(any(), anyString(), anyString(), any(Duration.class)))
                .thenReturn(new AiHttpTransport.Response(200, SUCCESS_BODY));

        userService.updateUserProfile(userId, Map.of("aiRecordEnabled", false));
        aiController.parseSchedule(request, Map.of("text", "明天学习", "recordUsage", true));
        assertThat(jdbc.queryForObject("select input_text from ai_usage_log order by id desc limit 1", String.class))
                .isNull();
        assertThat(jdbc.queryForObject("select output_text from ai_usage_log order by id desc limit 1", String.class))
                .isNull();

        userService.updateUserProfile(userId, Map.of("aiRecordEnabled", true));
        aiController.parseSchedule(request, Map.of("text", "后天复习", "recordUsage", false));
        assertThat(jdbc.queryForObject("select input_text from ai_usage_log order by id desc limit 1", String.class))
                .contains("后天复习");
        assertThat(jdbc.queryForObject("select output_text from ai_usage_log order by id desc limit 1", String.class))
                .contains("学习");
    }

    private long register(String phone) {
        return authService.register(phone, "Abc12345", "AI Preference User", "Asia/Shanghai");
    }

    private HttpServletRequest authorizedRequest(long userId) {
        HttpServletRequest request = mock(HttpServletRequest.class);
        when(request.getHeader("Authorization")).thenReturn("Bearer " + authService.issueAccessToken(userId));
        return request;
    }
}
