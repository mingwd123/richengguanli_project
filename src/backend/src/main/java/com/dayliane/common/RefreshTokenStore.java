package com.dayliane.common;

import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

@Component
public class RefreshTokenStore {

    private final Map<String, TokenEntry> store = new ConcurrentHashMap<>();
    private final Set<String> invalidAccessTokens = ConcurrentHashMap.newKeySet();

    public void save(String jti, long userId) {
        store.put(jti, new TokenEntry(userId, Instant.now().getEpochSecond()));
    }

    public boolean isValid(String jti, long userId) {
        TokenEntry entry = store.get(jti);
        if (entry == null) return false;
        if (entry.userId != userId) return false;
        // 7-day TTL check
        if (Instant.now().getEpochSecond() - entry.createdAt > 604_800) {
            store.remove(jti);
            return false;
        }
        return true;
    }

    public void invalidate(String jti) {
        store.remove(jti);
    }

    public void invalidateAccessToken(String jti) {
        invalidAccessTokens.add(jti);
    }

    public boolean isAccessTokenInvalidated(String jti) {
        return invalidAccessTokens.contains(jti);
    }

    public void invalidateAllForUser(long userId) {
        store.entrySet().removeIf(e -> e.getValue().userId == userId);
    }

    public void cleanupExpired() {
        long now = Instant.now().getEpochSecond();
        store.entrySet().removeIf(e -> now - e.getValue().createdAt > 604_800);
    }

    private record TokenEntry(long userId, long createdAt) {}
}
