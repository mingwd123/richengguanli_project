package com.dayliane.admin;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.AppenderBase;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

@Component
public class RecentOperationalMetrics extends OncePerRequestFilter {
    private final Bucket[] buckets = new Bucket[16];
    private final Instant startedAt = Instant.now();
    private Logger root;
    private final AppenderBase<ILoggingEvent> appender = new AppenderBase<>() {
        @Override protected void append(ILoggingEvent event) {
            recordLog(event.getLevel().isGreaterOrEqual(Level.ERROR), event.getLevel().isGreaterOrEqual(Level.WARN));
        }
    };

    @PostConstruct
    void attach() {
        if (LoggerFactory.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME) instanceof Logger logger) {
            root = logger;
            appender.setContext(root.getLoggerContext());
            appender.setName("admin-recent-log-counts");
            appender.start();
            root.addAppender(appender);
        }
    }

    @PreDestroy
    void detach() {
        if (root != null) root.detachAppender(appender);
        appender.stop();
    }

    @Override protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        long start = System.nanoTime();
        boolean failed = true;
        try {
            chain.doFilter(request, response);
            failed = response.getStatus() >= 500;
        } finally {
            recordRequest((System.nanoTime() - start) / 1000000, failed);
        }
    }

    synchronized void recordRequest(long latencyMs, boolean error) {
        Bucket bucket = current();
        bucket.requests++;
        if (error) bucket.errors++;
        bucket.totalMs += latencyMs;
        bucket.maxMs = Math.max(bucket.maxMs, latencyMs);
    }

    private synchronized void recordLog(boolean error, boolean warning) {
        Bucket bucket = current();
        if (error) bucket.errorLogs++;
        else if (warning) bucket.warningLogs++;
    }

    public synchronized Map<String, Object> snapshot() {
        long minute = Instant.now().getEpochSecond() / 60;
        Bucket sum = new Bucket(minute);
        for (Bucket bucket : buckets) {
            if (bucket == null || bucket.minute < minute - 14 || bucket.minute > minute) continue;
            sum.requests += bucket.requests;
            sum.errors += bucket.errors;
            sum.totalMs += bucket.totalMs;
            sum.maxMs = Math.max(sum.maxMs, bucket.maxMs);
            sum.errorLogs += bucket.errorLogs;
            sum.warningLogs += bucket.warningLogs;
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("windowMinutes", 15);
        result.put("since", Instant.ofEpochSecond((minute - 14) * 60).isBefore(startedAt)
                ? startedAt.toString() : Instant.ofEpochSecond((minute - 14) * 60).toString());
        result.put("requests", sum.requests);
        result.put("errors", sum.errors);
        result.put("averageLatencyMs", sum.requests == 0 ? null : sum.totalMs / sum.requests);
        result.put("maxLatencyMs", sum.requests == 0 ? null : sum.maxMs);
        result.put("errorRate", sum.requests == 0 ? null : Math.round(sum.errors * 1000.0 / sum.requests) / 10.0);
        result.put("errorLogs", root == null ? null : sum.errorLogs);
        result.put("warningLogs", root == null ? null : sum.warningLogs);
        return result;
    }

    private Bucket current() {
        long minute = Instant.now().getEpochSecond() / 60;
        int slot = (int) (minute % buckets.length);
        if (buckets[slot] == null || buckets[slot].minute != minute) buckets[slot] = new Bucket(minute);
        return buckets[slot];
    }

    private static class Bucket {
        final long minute;
        long requests, errors, totalMs, maxMs, errorLogs, warningLogs;
        Bucket(long minute) { this.minute = minute; }
    }
}
