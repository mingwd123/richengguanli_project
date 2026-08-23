package com.dayliane.auth;

import com.dayliane.common.BusinessException;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest
@ActiveProfiles("test")
class EmailSenderUnavailableTests {
    @Autowired private AuthService authService;

    @Test
    void sendingCodeWithoutConfiguredProviderReturnsServiceUnavailable() {
        assertThatThrownBy(() -> authService.sendEmailCode(null, "user@example.com", "register", null,
                "203.0.113.30", "email-auth-test"))
                .isInstanceOf(BusinessException.class)
                .extracting(ex -> ((BusinessException) ex).getCode())
                .isEqualTo(503);
    }
}
