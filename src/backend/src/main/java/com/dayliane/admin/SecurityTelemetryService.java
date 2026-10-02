package com.dayliane.admin;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.dao.DuplicateKeyException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import jakarta.annotation.PreDestroy;
import java.net.InetAddress;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.LinkedHashMap;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicLong;

@Service
public class SecurityTelemetryService {
    private final JdbcTemplate jdbc;
    private static final Logger log = LoggerFactory.getLogger(SecurityTelemetryService.class);
    private final AtomicLong written = new AtomicLong(), failed = new AtomicLong(), dropped = new AtomicLong();
    private final AtomicLong lastWarning = new AtomicLong();
    private volatile Instant lastSuccess;
    private final Map<Activity, Boolean> completed = new LinkedHashMap<>();
    private final Set<Activity> pending = new HashSet<>();
    private record Activity(long userId, LocalDate day) {}
    private final ThreadPoolExecutor writer = new ThreadPoolExecutor(1, 1, 0, TimeUnit.SECONDS,
            new ArrayBlockingQueue<>(2048), runnable -> {
        Thread thread = new Thread(runnable, "security-telemetry-writer");
        thread.setDaemon(true);
        return thread;
    }, new ThreadPoolExecutor.AbortPolicy());

    public SecurityTelemetryService(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    public void loginFailure(Long userId, String account, String ip) {
        Instant occurredAt = Instant.now();
        submit(() -> writeLoginFailure(userId, account, ip, occurredAt));
    }

    private void writeLoginFailure(Long userId, String account, String ip, Instant occurredAt) {
        String hash = accountHash(account);
        String address = address(ip);
        long recent = jdbc.queryForObject("select count(*) from security_event where event_type='login_failure' "
                        + "and (account_hash=? or ip_address=?) and created_at>=?", Long.class,
                hash, address, Timestamp.from(occurredAt.minusSeconds(600)));
        record(userId, hash, "login_failure", address, recent >= 4 ? "high" : "medium",
                recent >= 4 ? "frequent_failures" : "invalid_credentials", occurredAt);
    }

    public void loginSuccess(long userId, String account, String ip) {
        Instant occurredAt = Instant.now();
        submit(() -> writeLoginSuccess(userId, account, ip, occurredAt));
        activity(userId, occurredAt);
    }

    private void writeLoginSuccess(long userId, String account, String ip, Instant occurredAt) {
        String address = address(ip);
        List<String> previous = jdbc.queryForList("select ip_segment from security_event where user_id=? "
                        + "and event_type='login_success' and created_at>=? order by id desc limit 1",
                String.class, userId, Timestamp.from(occurredAt.minusSeconds(86400)));
        boolean changed = !previous.isEmpty() && !"unknown".equals(segment(address))
                && !"unknown".equals(previous.get(0)) && !previous.get(0).equals(segment(address));
        record(userId, accountHash(account), "login_success", address, changed ? "medium" : "low",
                changed ? "network_changed" : "normal", occurredAt);
    }

    public void registration(long userId, String ip) {
        Instant occurredAt = Instant.now();
        submit(() -> record(userId, null, "registration", address(ip), "low", "self_registration", occurredAt));
        activity(userId, occurredAt);
    }

    public void activity(long userId) {
        activity(userId, Instant.now());
    }

    private void activity(long userId, Instant occurredAt) {
        Activity key = new Activity(userId, occurredAt.atOffset(ZoneOffset.UTC).toLocalDate());
        synchronized (pending) {
            if (completed.containsKey(key) || !pending.add(key)) return;
        }
        boolean accepted = submit(() -> {
            try {
                try {
                    jdbc.update("insert into user_activity_daily (user_id,activity_date) values (?,?)", userId, key.day());
                } catch (DuplicateKeyException ignored) {
                    // Another instance may have already persisted this user-day.
                }
                synchronized (pending) {
                    completed.put(key, true);
                    completed.keySet().removeIf(item -> item.day().isBefore(key.day()));
                    while (completed.size() > 20000) completed.remove(completed.keySet().iterator().next());
                }
            } finally {
                synchronized (pending) { pending.remove(key); }
            }
        });
        if (!accepted) {
            synchronized (pending) { pending.remove(key); }
        }
    }

    private void record(Long userId, String hash, String type, String ip, String risk, String reason, Instant occurredAt) {
        jdbc.update("insert into security_event (user_id,account_hash,event_type,ip_address,ip_segment,risk_level,reason,created_at) "
                + "values (?,?,?,?,?,?,?,?)", userId, hash, type, ip, segment(ip), risk, reason, Timestamp.from(occurredAt));
    }

    private boolean submit(Runnable operation) {
        try {
            writer.execute(() -> {
                try { operation.run(); written.incrementAndGet(); lastSuccess = Instant.now(); }
                catch (RuntimeException ex) { failed.incrementAndGet(); warn(); }
            });
            return true;
        } catch (RejectedExecutionException ex) {
            dropped.incrementAndGet();
            warn();
            return false;
        }
    }

    private void warn() {
        long now = System.currentTimeMillis(), previous = lastWarning.get();
        if (now - previous > 60000 && lastWarning.compareAndSet(previous, now)) {
            log.warn("Telemetry write degraded: failed={}, dropped={}, queued={}", failed.get(), dropped.get(), writer.getQueue().size());
        }
    }

    public Map<String, Object> health() {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("written", written.get());
        result.put("failed", failed.get());
        result.put("dropped", dropped.get());
        result.put("queued", writer.getQueue().size());
        result.put("capacity", 2048);
        result.put("lastSuccessAt", lastSuccess == null ? null : lastSuccess.toString());
        return result;
    }

    @PreDestroy
    void shutdown() {
        writer.shutdown();
        try { if (!writer.awaitTermination(5, TimeUnit.SECONDS)) writer.shutdownNow(); }
        catch (InterruptedException ex) { writer.shutdownNow(); Thread.currentThread().interrupt(); }
    }

    private static String accountHash(String account) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest((account == null ? "" : account.trim().toLowerCase(Locale.ROOT)).getBytes(StandardCharsets.UTF_8)));
        } catch (Exception ex) { throw new IllegalStateException(ex); }
    }

    static String address(String ip) {
        if (ip == null || ip.length() > 45 || !ip.matches("[0-9a-fA-F:.]+")) return "unknown";
        try { return InetAddress.getByName(ip).getHostAddress(); }
        catch (Exception ex) { return "unknown"; }
    }

    static String segment(String ip) {
        try {
            if ("unknown".equals(ip)) return ip;
            byte[] bytes = InetAddress.getByName(ip).getAddress();
            if (bytes.length == 4) return (bytes[0] & 255) + "." + (bytes[1] & 255) + "." + (bytes[2] & 255) + ".0/24";
            for (int i = 6; i < bytes.length; i++) bytes[i] = 0;
            return InetAddress.getByAddress(bytes).getHostAddress() + "/48";
        } catch (Exception ex) { return "unknown"; }
    }
}
