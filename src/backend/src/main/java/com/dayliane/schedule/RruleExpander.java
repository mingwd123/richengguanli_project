package com.dayliane.schedule;

import com.dayliane.common.BusinessException;

import java.time.DateTimeException;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Locale;

/**
 * Minimal RFC 5545 RRULE expansion for personal recurring schedules.
 * Supports FREQ=DAILY/WEEKLY/MONTHLY with INTERVAL, BYDAY, BYMONTHDAY and UNTIL.
 * Instances are materialized per natural month, so COUNT is intentionally not expanded.
 */
final class RruleExpander {

    private RruleExpander() {}

    static List<LocalDate> expand(String rrule, LocalDate dtstart, LocalDate from, LocalDate to) {
        Rule rule = Rule.parse(rrule);
        if (from.isAfter(to)) return List.of();
        LocalDate end = rule.until != null && rule.until.isBefore(to) ? rule.until : to;
        List<LocalDate> dates = new ArrayList<>();
        LocalDate cursor = dtstart;
        while (!cursor.isAfter(end)) {
            if (!cursor.isBefore(from) && !cursor.equals(dtstart) && rule.matches(cursor, dtstart)) {
                dates.add(cursor);
            }
            cursor = cursor.plusDays(1);
        }
        return dates;
    }

    static void validate(String rrule) {
        Rule.parse(rrule);
    }

    private static final class Rule {
        enum Freq { DAILY, WEEKLY, MONTHLY }

        Freq freq;
        int interval = 1;
        EnumSet<DayOfWeek> weekdays = EnumSet.noneOf(DayOfWeek.class);
        List<OrdinalWeekday> monthOrdinals = new ArrayList<>();
        List<Integer> monthDays = new ArrayList<>();
        LocalDate until;

        boolean matches(LocalDate date, LocalDate dtstart) {
            return switch (freq) {
                case DAILY -> ChronoUnit.DAYS.between(dtstart, date) % interval == 0;
                case WEEKLY -> weeklyMatches(date, dtstart);
                case MONTHLY -> monthlyMatches(date, dtstart);
            };
        }

        private boolean weeklyMatches(LocalDate date, LocalDate dtstart) {
            LocalDate weekStart = dtstart.minusDays(dtstart.getDayOfWeek().getValue() - 1L);
            long weekIndex = ChronoUnit.DAYS.between(weekStart, date) / 7;
            if (weekIndex % interval != 0) return false;
            if (!weekdays.isEmpty()) return weekdays.contains(date.getDayOfWeek());
            return date.getDayOfWeek() == dtstart.getDayOfWeek();
        }

        private boolean monthlyMatches(LocalDate date, LocalDate dtstart) {
            long monthIndex = ChronoUnit.MONTHS.between(YearMonth.from(dtstart), YearMonth.from(date));
            if (monthIndex % interval != 0) return false;
            if (!monthDays.isEmpty()) return matchesMonthDay(date);
            if (!monthOrdinals.isEmpty()) {
                for (OrdinalWeekday ordinal : monthOrdinals) if (ordinal.matches(date)) return true;
                return false;
            }
            if (!weekdays.isEmpty()) return weekdays.contains(date.getDayOfWeek());
            return date.getDayOfMonth() == dtstart.getDayOfMonth();
        }

        private boolean matchesMonthDay(LocalDate date) {
            int dayOfMonth = date.getDayOfMonth();
            int lengthOfMonth = date.lengthOfMonth();
            for (int md : monthDays) {
                int day = md > 0 ? md : lengthOfMonth + md + 1;
                if (dayOfMonth == day) return true;
            }
            return false;
        }

        static Rule parse(String rrule) {
            Rule rule = new Rule();
            boolean hasFreq = false;
            if (rrule == null || rrule.isBlank()) throw new BusinessException(400, "rrule is invalid");
            String[] parts = rrule.trim().split(";");
            for (String rawPart : parts) {
                String part = rawPart.trim();
                if (part.isEmpty()) continue;
                int eq = part.indexOf('=');
                if (eq <= 0) throw new BusinessException(400, "rrule is invalid");
                String key = part.substring(0, eq).trim().toUpperCase(Locale.ROOT);
                String value = part.substring(eq + 1).trim();
                switch (key) {
                    case "FREQ" -> {
                        rule.freq = switch (value.toUpperCase(Locale.ROOT)) {
                            case "DAILY" -> Freq.DAILY;
                            case "WEEKLY" -> Freq.WEEKLY;
                            case "MONTHLY" -> Freq.MONTHLY;
                            default -> throw new BusinessException(400, "rrule is invalid");
                        };
                        hasFreq = true;
                    }
                    case "INTERVAL" -> {
                        rule.interval = parseInt(value, 1, 1000, "rrule is invalid");
                    }
                    case "BYDAY" -> rule.parseByDay(value);
                    case "BYMONTHDAY" -> rule.parseByMonthDay(value);
                    case "UNTIL" -> rule.until = parseUntil(value);
                    default -> {
                        // COUNT and other extensions are tolerated but not expanded per-month.
                    }
                }
            }
            if (!hasFreq) throw new BusinessException(400, "rrule is invalid");
            return rule;
        }

        private void parseByDay(String value) {
            for (String token : value.split(",")) {
                String t = token.trim().toUpperCase(Locale.ROOT);
                if (t.isEmpty()) continue;
                int split = 0;
                while (split < t.length() && !Character.isLetter(t.charAt(split))) split++;
                String prefix = t.substring(0, split);
                String weekName = t.substring(split);
                DayOfWeek day = dayOfWeek(weekName);
                if (prefix.isEmpty()) {
                    weekdays.add(day);
                } else {
                    monthOrdinals.add(new OrdinalWeekday(parseInt(prefix, -53, 53, "rrule is invalid"), day));
                }
            }
        }

        private void parseByMonthDay(String value) {
            for (String token : value.split(",")) {
                String t = token.trim();
                if (t.isEmpty()) continue;
                monthDays.add(parseInt(t, -31, 31, "rrule is invalid"));
            }
        }
    }

    private static final class OrdinalWeekday {
        final int ordinal;
        final DayOfWeek day;

        OrdinalWeekday(int ordinal, DayOfWeek day) {
            this.ordinal = ordinal;
            this.day = day;
        }

        boolean matches(LocalDate date) {
            if (date.getDayOfWeek() != day) return false;
            if (ordinal == 0) return true;
            if (ordinal > 0) {
                int count = (date.getDayOfMonth() - 1) / 7 + 1;
                return count == ordinal;
            }
            int fromEnd = date.lengthOfMonth() - date.getDayOfMonth();
            return fromEnd / 7 + 1 == -ordinal;
        }
    }

    private static DayOfWeek dayOfWeek(String name) {
        return switch (name) {
            case "MO" -> DayOfWeek.MONDAY;
            case "TU" -> DayOfWeek.TUESDAY;
            case "WE" -> DayOfWeek.WEDNESDAY;
            case "TH" -> DayOfWeek.THURSDAY;
            case "FR" -> DayOfWeek.FRIDAY;
            case "SA" -> DayOfWeek.SATURDAY;
            case "SU" -> DayOfWeek.SUNDAY;
            default -> throw new BusinessException(400, "rrule is invalid");
        };
    }

    private static int parseInt(String value, int min, int max, String message) {
        try {
            int parsed = Integer.parseInt(value.trim());
            if (parsed < min || parsed > max) throw new BusinessException(400, message);
            return parsed;
        } catch (NumberFormatException ex) {
            throw new BusinessException(400, message);
        }
    }

    private static LocalDate parseUntil(String value) {
        String v = value.trim();
        try {
            int y = Integer.parseInt(v.substring(0, 4));
            int m = Integer.parseInt(v.substring(4, 6));
            int d = Integer.parseInt(v.substring(6, 8));
            return LocalDate.of(y, m, d);
        } catch (DateTimeException | IndexOutOfBoundsException | NumberFormatException ex) {
            throw new BusinessException(400, "rrule is invalid");
        }
    }
}