package com.dayliane.ticket;

import com.dayliane.common.BusinessException;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 工单动作的进程内滑动窗口限流。
 *
 * 项目当前为单实例部署（与 {@link com.dayliane.common.LoginRateLimiter} 同一前提），
 * 限流状态保存在进程内即可；若未来横向扩容，需要把这层换成集中存储实现。
 */
@Component
public class TicketRateLimiter {

    private record Attempt(long windowStartSecond, int minuteCount, int dayCount, long dayStartSecond) {
        Attempt bumpMinute(long now, int limitPerMinute) {
            if (now - windowStartSecond >= 60) return new Attempt(now, 1, dayCount, dayStartSecond);
            return new Attempt(windowStartSecond, minuteCount + 1, dayCount, dayStartSecond);
        }

        Attempt bumpDay(long now, long dayLengthSecond) {
            if (now - dayStartSecond >= dayLengthSecond) {
                return new Attempt(now, minuteCount, 1, now);
            }
            return new Attempt(windowStartSecond, minuteCount, dayCount + 1, dayStartSecond);
        }
    }

    private final Map<String, Attempt> attempts = new ConcurrentHashMap<>();

    /** 校验并在通过后记账；任一窗口超限抛 429。 */
    public void checkAndRecord(long userId, String action, int limitPerMinute, int limitPerDay) {
        String key = userId + ":" + action;
        long now = Instant.now().getEpochSecond();
        Attempt current = attempts.get(key);
        if (current != null) {
            boolean minuteBlocked = now - current.windowStartSecond() < 60 && current.minuteCount() >= limitPerMinute;
            boolean dayBlocked = now - current.dayStartSecond() < 86_400 && current.dayCount() >= limitPerDay;
            if (minuteBlocked || dayBlocked) {
                throw new BusinessException(429, "too many requests, please retry later");
            }
        }
        Attempt next = current == null
                ? new Attempt(now, 1, 1, now)
                : current.bumpMinute(now, limitPerMinute).bumpDay(now, 86_400);
        attempts.put(key, next);
    }

    /** 校验通过返回剩余可上传字节数（当日），超限抛 429；调用方自行把本次字节数记入。 */
    public long checkUploadBytes(long userId, long perDayBytes) {
        String key = userId + ":upload_bytes";
        long now = Instant.now().getEpochSecond();
        Attempt current = attempts.get(key);
        long used = current != null && now - current.dayStartSecond() < 86_400 ? current.dayCount() : 0;
        if (used >= perDayBytes) {
            throw new BusinessException(429, "daily upload quota exceeded, please retry tomorrow");
        }
        return perDayBytes - used;
    }

    public void recordUploadBytes(long userId, long bytes) {
        String key = userId + ":upload_bytes";
        long now = Instant.now().getEpochSecond();
        Attempt current = attempts.get(key);
        Attempt next = current == null || now - current.dayStartSecond() >= 86_400
                ? new Attempt(now, 0, (int) Math.min(bytes, Integer.MAX_VALUE), now)
                : new Attempt(current.windowStartSecond(), current.minuteCount(),
                        (int) Math.min(current.dayCount() + bytes, Integer.MAX_VALUE), current.dayStartSecond());
        attempts.put(key, next);
    }

    /** 仅测试使用：清空全部窗口。 */
    void reset() {
        attempts.clear();
    }
}
