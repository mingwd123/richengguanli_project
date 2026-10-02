package com.dayliane.admin;

import com.dayliane.common.BusinessException;

import java.sql.Timestamp;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.Map;

public record AnalyticsRange(LocalDate from, LocalDate to) {
    public static AnalyticsRange parse(String from, String to) {
        try {
            LocalDate end = to == null || to.isBlank() ? LocalDate.now(ZoneOffset.UTC) : LocalDate.parse(to);
            LocalDate start = from == null || from.isBlank() ? end.minusDays(6) : LocalDate.parse(from);
            if (start.isAfter(end) || ChronoUnit.DAYS.between(start, end) > 365
                    || end.isAfter(LocalDate.now(ZoneOffset.UTC))) {
                throw new IllegalArgumentException();
            }
            return new AnalyticsRange(start, end);
        } catch (RuntimeException ex) {
            throw new BusinessException(400, "date range must be valid, not in the future, and at most 366 days");
        }
    }

    public Timestamp start() { return Timestamp.valueOf(from.atStartOfDay()); }
    public Timestamp end() { return Timestamp.valueOf(to.plusDays(1).atStartOfDay()); }
    public Map<String, Object> metadata() {
        return Map.of("dateFrom", from.toString(), "dateTo", to.toString(), "timezone", "UTC");
    }
}
