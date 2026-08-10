package com.dayliane.common;

import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.sql.Timestamp;
import java.time.Instant;

@Component
public class RefreshTokenStore {

    private static final long ACCESS_TTL_SECONDS = 86_400;
    private static final long REFRESH_TTL_SECONDS = 604_800;

    private final JdbcTemplate jdbc;

    public RefreshTokenStore(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public void save(String jti, long userId) {
        jdbc.update("insert into auth_refresh_token (jti,user_id,expires_at) values (?,?,?)",
                jti, userId, Timestamp.from(Instant.now().plusSeconds(REFRESH_TTL_SECONDS)));
    }

    public boolean consume(String jti, long userId) {
        return jdbc.update(
                "delete from auth_refresh_token where jti=? and user_id=? and expires_at>utc_timestamp()",
                jti, userId) == 1;
    }

    public void invalidate(String jti) {
        jdbc.update("delete from auth_refresh_token where jti=?", jti);
    }

    public void invalidateAccessToken(String jti) {
        try {
            jdbc.update("insert into auth_revoked_access_token (jti,expires_at) values (?,?)",
                    jti, Timestamp.from(Instant.now().plusSeconds(ACCESS_TTL_SECONDS)));
        } catch (DuplicateKeyException ignored) {
            // Revocation is idempotent.
        }
    }

    public boolean isAccessTokenInvalidated(String jti) {
        Integer count = jdbc.queryForObject(
                "select count(*) from auth_revoked_access_token where jti=? and expires_at>utc_timestamp()",
                Integer.class, jti);
        return count != null && count > 0;
    }

    public void invalidateAllForUser(long userId) {
        jdbc.update("delete from auth_refresh_token where user_id=?", userId);
    }

    @Scheduled(cron = "0 30 3 * * *")
    public void cleanupExpired() {
        jdbc.update("delete from auth_refresh_token where expires_at<=utc_timestamp()");
        jdbc.update("delete from auth_revoked_access_token where expires_at<=utc_timestamp()");
    }
}
