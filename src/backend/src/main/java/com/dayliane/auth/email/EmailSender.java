package com.dayliane.auth.email;

public interface EmailSender {
    void ensureReady();

    void send(EmailVerificationMessage message);
}
