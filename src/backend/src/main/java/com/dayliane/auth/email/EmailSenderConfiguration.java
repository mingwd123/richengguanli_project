package com.dayliane.auth.email;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
@EnableConfigurationProperties(EmailProperties.class)
public class EmailSenderConfiguration {
    @Bean
    @ConditionalOnProperty(prefix = "app.email.tencent-ses", name = "enabled", havingValue = "true")
    EmailSender tencentSesEmailSender(EmailProperties properties, ObjectMapper objectMapper) {
        return new TencentSesEmailSender(properties.getTencentSes(), objectMapper);
    }

    @Bean
    @ConditionalOnMissingBean(EmailSender.class)
    EmailSender unavailableEmailSender() {
        return new UnavailableEmailSender();
    }
}
