package com.dayliane.common;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Date;
import java.util.UUID;

@Component
public class JwtService {

    private final SecretKey signingKey;

    public JwtService(@Value("${app.jwt.secret:change-me-in-development}") String secret) {
        byte[] keyBytes = secret.getBytes(StandardCharsets.UTF_8);
        if (keyBytes.length < 32) {
            byte[] padded = new byte[32];
            System.arraycopy(keyBytes, 0, padded, 0, Math.min(keyBytes.length, 32));
            keyBytes = padded;
        }
        this.signingKey = Keys.hmacShaKeyFor(keyBytes);
    }

    public String issueAccessToken(long userId, int tokenVersion) {
        return issueToken(String.valueOf(userId), "access", tokenVersion, 86_400);
    }

    public String issueAdminAccessToken(long adminId) {
        return issueToken(String.valueOf(adminId), "admin_access", 0, 86_400);
    }

    public String issueRefreshToken(long userId, int tokenVersion) {
        return issueToken(String.valueOf(userId), "refresh", tokenVersion, 604_800);
    }

    private String issueToken(String sub, String tokenType, int tokenVersion, long ttlSeconds) {
        return Jwts.builder()
                .subject(sub)
                .claim("tokenType", tokenType)
                .claim("tokenVersion", tokenVersion)
                .issuedAt(Date.from(Instant.now()))
                .expiration(Date.from(Instant.now().plusSeconds(ttlSeconds)))
                .id(UUID.randomUUID().toString())
                .signWith(signingKey)
                .compact();
    }

    public long verifyAccessToken(String token) {
        return verifyToken(token, "access");
    }

    public long verifyAdminAccessToken(String token) {
        Claims claims = parseToken(token);
        if (!"admin_access".equals(claims.get("tokenType", String.class))) {
            throw new BusinessException(403, "forbidden");
        }
        return Long.parseLong(claims.getSubject());
    }

    public Long verifyRefreshToken(String token) {
        try {
            Claims claims = parseToken(token);
            if (!"refresh".equals(claims.get("tokenType", String.class))) {
                return null;
            }
            return Long.parseLong(claims.getSubject());
        } catch (Exception e) {
            return null;
        }
    }

    public String getJti(String token) {
        try {
            return parseToken(token).getId();
        } catch (Exception e) {
            return null;
        }
    }

    public int getTokenVersion(String token) {
        try {
            Number version = parseToken(token).get("tokenVersion", Number.class);
            return version == null ? -1 : version.intValue();
        } catch (Exception e) {
            return -1;
        }
    }

    private Claims parseToken(String token) {
        try {
            return Jwts.parser()
                    .verifyWith(signingKey)
                    .build()
                    .parseSignedClaims(token)
                    .getPayload();
        } catch (JwtException | IllegalArgumentException e) {
            throw new BusinessException(401, "unauthorized");
        }
    }

    private long verifyToken(String token, String expectedType) {
        Claims claims = parseToken(token);
        String tokenType = claims.get("tokenType", String.class);
        if (!expectedType.equals(tokenType)) {
            throw new BusinessException(403, "forbidden");
        }
        return Long.parseLong(claims.getSubject());
    }
}
