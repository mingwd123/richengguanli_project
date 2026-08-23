package com.dayliane.auth.email;

import com.dayliane.common.BusinessException;

public class EmailOtpVerificationException extends BusinessException {
    public EmailOtpVerificationException() {
        super(400, "email code is invalid or expired");
    }
}
