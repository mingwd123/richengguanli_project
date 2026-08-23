package com.dayliane.common;

import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Component
public class CurrentPasswordRateLimiter {
    private static final int MAX_ATTEMPTS = 5;
    private static final long WINDOW_SECONDS = 600;

    private final Map<Long, AttemptRecord> attempts = new ConcurrentHashMap<>();

    public void acquireAttempt(long userId) {
        long now = Instant.now().getEpochSecond();
        attempts.compute(userId, (key, record) -> {
            if (record == null || now - record.windowStart() > WINDOW_SECONDS) {
                return new AttemptRecord(now, 1);
            }
            if (record.count() >= MAX_ATTEMPTS) {
                throw new BusinessException(429, "too many current password attempts, please try again later");
            }
            return new AttemptRecord(record.windowStart(), record.count() + 1);
        });
    }

    public void clearSuccess(long userId) {
        attempts.remove(userId);
    }

    private record AttemptRecord(long windowStart, int count) {}
}
