package com.dayliane.common;

import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Component
public class LoginRateLimiter {

    private static final int MAX_ATTEMPTS = 5;
    private static final long WINDOW_SECONDS = 600; // 10 minutes

    private final Map<String, AttemptRecord> attempts = new ConcurrentHashMap<>();

    public void checkLimit(String ip, String username) {
        String key1 = key("ip", ip);
        String key2 = key("user", username);

        AttemptRecord r1 = attempts.get(key1);
        AttemptRecord r2 = attempts.get(key2);

        if (isBlocked(r1) || isBlocked(r2)) {
            throw new BusinessException(429, "too many login attempts, please try again later");
        }
    }

    public void recordFailure(String ip, String username) {
        record(key("ip", ip));
        record(key("user", username));
    }

    public void clearSuccess(String ip, String username) {
        attempts.remove(key("ip", ip));
        attempts.remove(key("user", username));
    }

    private void record(String key) {
        AttemptRecord record = attempts.get(key);
        long now = Instant.now().getEpochSecond();
        if (record == null || now - record.windowStart > WINDOW_SECONDS) {
            attempts.put(key, new AttemptRecord(now, 1));
        } else {
            record.count++;
        }
    }

    private boolean isBlocked(AttemptRecord record) {
        if (record == null) return false;
        long now = Instant.now().getEpochSecond();
        if (now - record.windowStart > WINDOW_SECONDS) return false;
        return record.count >= MAX_ATTEMPTS;
    }

    private static String key(String prefix, String value) {
        return prefix + ":" + value;
    }

    private static class AttemptRecord {
        final long windowStart;
        int count;

        AttemptRecord(long windowStart, int count) {
            this.windowStart = windowStart;
            this.count = count;
        }
    }
}
