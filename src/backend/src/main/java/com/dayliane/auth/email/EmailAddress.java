package com.dayliane.auth.email;

import com.dayliane.common.BusinessException;

import java.util.Locale;
import java.util.regex.Pattern;

public final class EmailAddress {
    private static final Pattern EMAIL_PATTERN = Pattern.compile(
            "^[A-Z0-9.!#$%&'*+/=?^_`{|}~-]+@[A-Z0-9](?:[A-Z0-9-]{0,61}[A-Z0-9])?(?:\\.[A-Z0-9](?:[A-Z0-9-]{0,61}[A-Z0-9])?)+$",
            Pattern.CASE_INSENSITIVE);

    private EmailAddress() {}

    public static String normalize(String raw) {
        String normalized = raw == null ? "" : raw.trim().toLowerCase(Locale.ROOT);
        if (normalized.length() > 254 || !EMAIL_PATTERN.matcher(normalized).matches()) {
            throw new BusinessException(400, "email format is invalid");
        }
        return normalized;
    }

    public static String tryNormalize(String raw) {
        try {
            return normalize(raw);
        } catch (BusinessException ignored) {
            return null;
        }
    }
}
