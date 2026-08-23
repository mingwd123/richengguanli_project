package com.dayliane.auth.email;

public record EmailVerificationMessage(
        String recipient,
        EmailCodePurpose purpose,
        String code,
        long expiresInMinutes,
        String subject,
        String plainText,
        String html
) {}
