package com.dayliane.auth.email;

import com.dayliane.common.BusinessException;

final class UnavailableEmailSender implements EmailSender {
    @Override
    public void ensureReady() {
        throw new BusinessException(503, "email delivery service is unavailable");
    }

    @Override
    public void send(EmailVerificationMessage message) {
        ensureReady();
    }
}
