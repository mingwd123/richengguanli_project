package com.dayliane.auth.email;

import com.dayliane.common.BusinessException;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.tencentcloudapi.common.Credential;
import com.tencentcloudapi.common.exception.TencentCloudSDKException;
import com.tencentcloudapi.common.profile.ClientProfile;
import com.tencentcloudapi.common.profile.HttpProfile;
import com.tencentcloudapi.ses.v20201002.SesClient;
import com.tencentcloudapi.ses.v20201002.models.SendEmailRequest;
import com.tencentcloudapi.ses.v20201002.models.Template;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;
import java.util.Set;

final class TencentSesEmailSender implements EmailSender {
    private static final Logger log = LoggerFactory.getLogger(TencentSesEmailSender.class);
    private static final Set<String> SUPPORTED_REGIONS = Set.of("ap-guangzhou", "ap-hongkong");

    private final EmailProperties.TencentSes properties;
    private final ObjectMapper objectMapper;

    TencentSesEmailSender(EmailProperties.TencentSes properties, ObjectMapper objectMapper) {
        this.properties = properties;
        this.objectMapper = objectMapper;
    }

    @Override
    public void ensureReady() {
        if (!properties.isEnabled()
                || blank(properties.getSecretId())
                || blank(properties.getSecretKey())
                || blank(properties.getFromEmail())
                || blank(properties.getTemplateId())
                || !SUPPORTED_REGIONS.contains(properties.getRegion())) {
            throw new BusinessException(503, "email delivery service is unavailable");
        }
        templateId();
    }

    @Override
    public void send(EmailVerificationMessage message) {
        ensureReady();
        try {
            Credential credential = new Credential(properties.getSecretId(), properties.getSecretKey());
            HttpProfile httpProfile = new HttpProfile();
            httpProfile.setConnTimeout(5);
            httpProfile.setReadTimeout(10);
            httpProfile.setWriteTimeout(10);
            ClientProfile clientProfile = new ClientProfile();
            clientProfile.setHttpProfile(httpProfile);
            SesClient client = new SesClient(credential, properties.getRegion(), clientProfile);
            SendEmailRequest request = new SendEmailRequest();
            request.setFromEmailAddress(properties.getFromEmail());
            request.setDestination(new String[]{message.recipient()});
            request.setSubject(message.subject());
            if (!blank(properties.getReplyTo())) request.setReplyToAddresses(properties.getReplyTo());
            request.setTriggerType(1L);

            Template template = new Template();
            template.setTemplateID(templateId());
            template.setTemplateData(templateData(message));
            request.setTemplate(template);
            client.SendEmail(request);
        } catch (TencentCloudSDKException | JsonProcessingException | RuntimeException ex) {
            if (ex instanceof BusinessException businessException) throw businessException;
            log.warn("Tencent SES delivery failed for email code purpose {}", message.purpose().value());
            throw new BusinessException(503, "email delivery service is unavailable");
        }
    }

    private String templateData(EmailVerificationMessage message) throws JsonProcessingException {
        return objectMapper.writeValueAsString(Map.of(
                "code", message.code(),
                "minutes", Long.toString(message.expiresInMinutes()),
                "purpose", message.purpose().value(),
                "title", message.subject(),
                "description", message.purpose().description()
        ));
    }

    private long templateId() {
        try {
            long value = Long.parseLong(properties.getTemplateId().trim());
            if (value <= 0) throw new NumberFormatException();
            return value;
        } catch (NumberFormatException ex) {
            throw new BusinessException(503, "email delivery service is unavailable");
        }
    }

    private static boolean blank(String value) {
        return value == null || value.isBlank();
    }
}
