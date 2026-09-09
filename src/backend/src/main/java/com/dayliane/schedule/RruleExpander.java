package com.dayliane.schedule;

import com.dayliane.common.BusinessException;

import java.time.DateTimeException;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Strict RRULE subset used by personal recurring schedules.
 * Supports FREQ=DAILY/WEEKLY/MONTHLY with INTERVAL, BYDAY, BYMONTHDAY and
 * date-only UNTIL. Unsupported RFC 5545 fields are rejected instead of being
 * silently ignored.
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
            String[] parts = rrule.trim().split(";", -1);
            Set<String> seenKeys = new HashSet<>();
            for (String rawPart : parts) {
                String part = rawPart.trim();
                if (part.isEmpty()) throw new BusinessException(400, "rrule is invalid");
                int eq = part.indexOf('=');
                if (eq <= 0 || eq != part.lastIndexOf('=')) throw new BusinessException(400, "rrule is invalid");
                String key = part.substring(0, eq).trim().toUpperCase(Locale.ROOT);
                String value = part.substring(eq + 1).trim();
                if (value.isEmpty() || !seenKeys.add(key)) throw new BusinessException(400, "rrule is invalid");
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
                    default -> throw new BusinessException(400, "rrule is invalid");
                }
            }
            if (!hasFreq) throw new BusinessException(400, "rrule is invalid");
            rule.validateCombinations();
            return rule;
        }

        private void parseByDay(String value) {
            for (String token : value.split(",", -1)) {
                String t = token.trim().toUpperCase(Locale.ROOT);
                if (t.isEmpty()) throw new BusinessException(400, "rrule is invalid");
                int split = 0;
                while (split < t.length() && !Character.isLetter(t.charAt(split))) split++;
                String prefix = t.substring(0, split);
                String weekName = t.substring(split);
                DayOfWeek day = dayOfWeek(weekName);
                if (prefix.isEmpty()) {
                    if (!weekdays.add(day)) throw new BusinessException(400, "rrule is invalid");
                } else {
                    int ordinal = parseInt(prefix, -5, 5, "rrule is invalid");
                    if (ordinal == 0) throw new BusinessException(400, "rrule is invalid");
                    boolean duplicate = monthOrdinals.stream().anyMatch(item -> item.ordinal == ordinal && item.day == day);
                    if (duplicate) throw new BusinessException(400, "rrule is invalid");
                    monthOrdinals.add(new OrdinalWeekday(ordinal, day));
                }
            }
        }

        private void parseByMonthDay(String value) {
            for (String token : value.split(",", -1)) {
                String t = token.trim();
                if (t.isEmpty()) throw new BusinessException(400, "rrule is invalid");
                int day = parseInt(t, -31, 31, "rrule is invalid");
                if (day == 0 || monthDays.contains(day)) throw new BusinessException(400, "rrule is invalid");
                monthDays.add(day);
            }
        }

        private void validateCombinations() {
            switch (freq) {
                case DAILY -> {
                    if (!weekdays.isEmpty() || !monthOrdinals.isEmpty() || !monthDays.isEmpty()) {
                        throw new BusinessException(400, "rrule is invalid");
                    }
                }
                case WEEKLY -> {
                    if (!monthOrdinals.isEmpty() || !monthDays.isEmpty()) {
                        throw new BusinessException(400, "rrule is invalid");
                    }
                }
                case MONTHLY -> {
                    boolean hasByDay = !weekdays.isEmpty() || !monthOrdinals.isEmpty();
                    if (!monthDays.isEmpty() && hasByDay) throw new BusinessException(400, "rrule is invalid");
                    if (!weekdays.isEmpty() && !monthOrdinals.isEmpty()) throw new BusinessException(400, "rrule is invalid");
                }
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
        if (!v.matches("\\d{8}")) throw new BusinessException(400, "rrule is invalid");
        try {
            return LocalDate.parse(v, java.time.format.DateTimeFormatter.BASIC_ISO_DATE);
        } catch (DateTimeException ex) {
            throw new BusinessException(400, "rrule is invalid");
        }
    }
}
