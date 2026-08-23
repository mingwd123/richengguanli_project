package com.dayliane.common;

import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.HashMap;
import java.util.Map;

@Component
public class UserLoginRateLimiter {
    private static final int MAX_ATTEMPTS = 5;
    private static final long WINDOW_SECONDS = 600;

    private final Map<String, AttemptRecord> attempts = new HashMap<>();

    public synchronized void acquireAttempt(String ip, String account) {
        long now = Instant.now().getEpochSecond();
        String ipKey = key("ip", ip);
        String accountKey = key("user", account);
        AttemptRecord ipRecord = activeRecord(ipKey, now);
        AttemptRecord accountRecord = activeRecord(accountKey, now);
        if (blocked(ipRecord) || blocked(accountRecord)) {
            throw new BusinessException(429, "too many login attempts, please try again later");
        }
        attempts.put(ipKey, increment(ipRecord, now));
        attempts.put(accountKey, increment(accountRecord, now));
    }

    public synchronized void clearSuccess(String ip, String account) {
        attempts.remove(key("ip", ip));
        attempts.remove(key("user", account));
    }

    private AttemptRecord activeRecord(String key, long now) {
        AttemptRecord record = attempts.get(key);
        if (record != null && now - record.windowStart() > WINDOW_SECONDS) {
            attempts.remove(key);
            return null;
        }
        return record;
    }

    private static AttemptRecord increment(AttemptRecord record, long now) {
        return record == null
                ? new AttemptRecord(now, 1)
                : new AttemptRecord(record.windowStart(), record.count() + 1);
    }

    private static boolean blocked(AttemptRecord record) {
        return record != null && record.count() >= MAX_ATTEMPTS;
    }

    private static String key(String prefix, String value) {
        return prefix + ":" + value;
    }

    private record AttemptRecord(long windowStart, int count) {}
}
