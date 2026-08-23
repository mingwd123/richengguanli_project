package com.dayliane.auth.email;

import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

@Component
public class VerificationEmailRenderer {
    private final String template;

    public VerificationEmailRenderer() {
        try {
            template = new ClassPathResource("email/verification-code.html")
                    .getContentAsString(StandardCharsets.UTF_8);
        } catch (IOException ex) {
            throw new IllegalStateException("verification email template is unavailable", ex);
        }
    }

    public EmailVerificationMessage render(String recipient, EmailCodePurpose purpose, String code, long expiresInMinutes) {
        String html = template
                .replace("{{title}}", escapeHtml(purpose.subject()))
                .replace("{{description}}", escapeHtml(purpose.description()))
                .replace("{{code}}", code)
                .replace("{{minutes}}", Long.toString(expiresInMinutes));
        String plainText = purpose.description() + System.lineSeparator()
                + "验证码：" + code + System.lineSeparator()
                + "验证码将在 " + expiresInMinutes + " 分钟后失效，仅可使用一次。"
                + System.lineSeparator() + "请勿将验证码透露给任何人。";
        return new EmailVerificationMessage(recipient, purpose, code, expiresInMinutes,
                purpose.subject(), plainText, html);
    }

    private static String escapeHtml(String value) {
        return value.replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;")
                .replace("\"", "&quot;")
                .replace("'", "&#39;");
    }
}
