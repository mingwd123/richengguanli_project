package com.dayliane.auth.email;

import com.dayliane.common.BusinessException;

import java.util.Locale;

public enum EmailCodePurpose {
    REGISTER("register", "Dayliane 注册验证码", "您好，您正在注册 Dayliane 账号。"),
    BIND_EMAIL("bind_email", "Dayliane 绑定邮箱验证码", "您好，您正在为 Dayliane 账号绑定邮箱。"),
    CHANGE_EMAIL("change_email", "Dayliane 修改邮箱验证码", "您好，您正在修改 Dayliane 账号的邮箱。"),
    RESET_PASSWORD("reset_password", "Dayliane 找回密码验证码", "您好，您正在重置 Dayliane 账号密码。");

    private final String value;
    private final String subject;
    private final String description;

    EmailCodePurpose(String value, String subject, String description) {
        this.value = value;
        this.subject = subject;
        this.description = description;
    }

    public String value() {
        return value;
    }

    public String subject() {
        return subject;
    }

    public String description() {
        return description;
    }

    public boolean requiresAuthentication() {
        return this == BIND_EMAIL || this == CHANGE_EMAIL;
    }

    public static EmailCodePurpose parse(String raw) {
        String value = raw == null ? "" : raw.trim().toLowerCase(Locale.ROOT);
        for (EmailCodePurpose purpose : values()) {
            if (purpose.value.equals(value)) return purpose;
        }
        throw new BusinessException(400, "email code purpose is invalid");
    }
}
