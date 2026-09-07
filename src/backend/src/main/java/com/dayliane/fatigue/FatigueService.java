package com.dayliane.fatigue;

import com.dayliane.common.BusinessException;
import com.dayliane.notification.NotificationService;
import com.dayliane.schedule.ScheduleService;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Time;
import java.sql.Timestamp;
import java.time.DateTimeException;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

@Service
public class FatigueService {
    private static final int ALGORITHM_VERSION = 2;
    private static final BigDecimal DEFAULT_CAPACITY = BigDecimal.valueOf(18);
    private static final int DEFAULT_SURVEY_HOUR = 21;
    private static final int DEFAULT_SURVEY_MINUTE = 30;
    private static final int MAX_HISTORY_RANGE_DAYS = 366;
    private static final Set<String> STRONG_FACTOR_TAGS = Set.of("身体不适", "存在未记录任务");
    private static final String DISCLAIMER = "疲劳结果仅用于个人日程规划参考，不构成医疗诊断或健康建议。";

    private final JdbcTemplate jdbc;
    private final NotificationService notificationService;
    private final ObjectMapper objectMapper;
    private final ObjectProvider<ScheduleService> scheduleServiceProvider;

    @Value("${dayliane.features.fatigue.enabled:true}")
    private boolean fatigueFeatureEnabled;

    @Value("${dayliane.features.fatigue.alerts-enabled:true}")
    private boolean fatigueAlertsFeatureEnabled;

    @Value("${dayliane.features.fatigue.surveys-enabled:true}")
    private boolean fatigueSurveysFeatureEnabled;

    @Value("${dayliane.features.fatigue.learning-enabled:true}")
    private boolean fatigueLearningFeatureEnabled;

    @Value("${dayliane.features.fatigue.retention-days:180}")
    private int retentionDays;

    @Value("${dayliane.features.fatigue.allowed-user-ids:}")
    private String fatigueAllowedUserIds;

    public FatigueService(JdbcTemplate jdbc, NotificationService notificationService, ObjectMapper objectMapper, ObjectProvider<ScheduleService> scheduleServiceProvider) {
        this.jdbc = jdbc;
        this.notificationService = notificationService;
        this.objectMapper = objectMapper;
        this.scheduleServiceProvider = scheduleServiceProvider;
    }

    @Transactional
    public Map<String, Object> daily(long userId, LocalDate date) {
        ensureProfile(userId);
        ZoneId zone = userZone(userId);
        Map<String, Object> profile = profileRow(userId);
        if (!trackingEnabled(profile)) return disabledDaily(userId, date, zone, profile);
        return dailyResult(userId, date, zone, profile, number(profile.get("dataRevision"), 0L), true);
    }

    @Transactional
    public Map<String, Object> daily(long userId) {
        ZoneId zone = userZone(userId);
        return daily(userId, LocalDate.now(zone));
    }

    public Map<String, Object> daily(long userId, String date) {
        return daily(userId, parseDate(date, "date"));
    }

    @Transactional
    public void recalculateDates(long userId, List<LocalDate> dates) {
        recalculateDates(userId, dates, false);
    }

    @Transactional
    public void recalculateDates(long userId, List<LocalDate> dates, boolean invalidateTraining) {
        List<LocalDate> affected = distinctDates(dates);
        ensureProfile(userId);
        incrementDataRevision(userId);
        if (affected.isEmpty()) return;
        if (invalidateTraining && invalidateSurveysForDates(userId, affected) > 0) {
            recalibrateModelInternal(userId);
        }
        Map<String, Object> profile = profileRow(userId);
        if (!trackingEnabled(profile)) return;
        ZoneId zone = userZone(userId);
        long revision = number(profile.get("dataRevision"), 0L);
        for (LocalDate date : affected) {
            dailyResult(userId, date, zone, profile, revision, true);
        }
    }

    @Transactional
    public void timezoneChanged(long userId, String previousTimezone, String nextTimezone) {
        ZoneId previous = safeZone(previousTimezone);
        ZoneId next = safeZone(nextTimezone);
        Instant now = Instant.now();
        LinkedHashSet<LocalDate> dates = new LinkedHashSet<>();
        LinkedHashSet<LocalDate> timezoneUncertainDates = new LinkedHashSet<>();
        timezoneUncertainDates.add(now.atZone(previous).toLocalDate());
        timezoneUncertainDates.add(now.atZone(next).toLocalDate());
        timezoneUncertainDates.add(now.atZone(previous).toLocalDate().minusDays(1));
        timezoneUncertainDates.add(now.atZone(next).toLocalDate().minusDays(1));
        dates.addAll(timezoneUncertainDates);
        ensureProfile(userId);
        LinkedHashSet<LocalDate> trainingDates = new LinkedHashSet<>(timezoneUncertainDates);
        for (Map<String, Object> schedule : schedulesForUser(userId)) {
            LocalDate previousPlanned = schedulePlannedDate(schedule, previous);
            LocalDate nextPlanned = schedulePlannedDate(schedule, next);
            if (previousPlanned != null) dates.add(previousPlanned);
            if (nextPlanned != null) dates.add(nextPlanned);

            LocalDate previousCompleted = localDate(schedule.get("completedAt"), previous);
            LocalDate nextCompleted = localDate(schedule.get("completedAt"), next);
            if (previousCompleted != null) dates.add(previousCompleted);
            if (nextCompleted != null) dates.add(nextCompleted);
            if (!Objects.equals(previousCompleted, nextCompleted)) {
                if (previousCompleted != null) trainingDates.add(previousCompleted);
                if (nextCompleted != null) trainingDates.add(nextCompleted);
            }
        }
        dates.addAll(jdbc.query("select local_date from fatigue_daily_summary where user_id=?", (rs, index) -> rs.getDate(1).toLocalDate(), userId));
        dates.addAll(jdbc.query("select local_date from fatigue_survey where user_id=?", (rs, index) -> rs.getDate(1).toLocalDate(), userId));

        int invalidated = 0;
        for (LocalDate date : timezoneUncertainDates) {
            invalidated += jdbc.update("update fatigue_survey set model_eligible=false,learning_weight=0,ineligible_reason='timezone_changed',capacity_after=null,updated_at=utc_timestamp() where user_id=? and local_date=?",
                    userId, date);
        }
        invalidated += invalidateSurveysForDates(userId, List.copyOf(trainingDates));
        if (invalidated > 0) recalibrateModelInternal(userId);
        recalculateDates(userId, List.copyOf(dates), false);
    }

    @Transactional
    public long currentDataRevision(long userId) {
        ensureProfile(userId);
        Long revision = jdbc.queryForObject("select data_revision from user_fatigue_profile where user_id=?", Long.class, userId);
        return revision == null ? 0L : revision;
    }

    @Transactional
    public long touchDataRevision(long userId) {
        ensureProfile(userId);
        incrementDataRevision(userId);
        Long revision = jdbc.queryForObject("select data_revision from user_fatigue_profile where user_id=?", Long.class, userId);
        return revision == null ? 0L : revision;
    }

    @Transactional
    public BigDecimal currentWeight(long userId, int level) {
        validateLevel(level);
        ensureProfile(userId);
        return scale(weights(profileRow(userId)).getOrDefault(level, BigDecimal.valueOf(defaultWeight(level))));
    }

    @Transactional
    public boolean trackingEnabledFor(long userId) {
        ensureProfile(userId);
        return trackingEnabled(profileRow(userId));
    }

    @Transactional
    public Map<String, Object> profile(long userId) {
        ensureProfile(userId);
        return profileResponse(profileRow(userId));
    }

    @Transactional
    public Map<String, Object> updatePreferences(long userId, Map<String, Object> req) {
        ensureProfile(userId);
        Map<String, Object> current = profileRow(userId);
        boolean tracking = bool(req, "fatigueTrackingEnabled", current, true);
        boolean alerts = bool(req, "fatigueAlertEnabled", current, true);
        boolean survey = bool(req, "surveyEnabled", current, true);
        boolean locked = bool(req, "capacityLocked", current, false);
        LocalTime surveyTime = parseSurveyTime(req.containsKey("surveyTime") ? req.get("surveyTime") : current.get("surveyTime"));
        jdbc.update("update user_fatigue_profile set fatigue_tracking_enabled=?,fatigue_alert_enabled=?,survey_enabled=?,survey_time=?,capacity_locked=?,data_revision=data_revision+1,updated_at=utc_timestamp() where user_id=?",
                tracking, alerts, survey, Time.valueOf(surveyTime), locked, userId);
        if (tracking && (!boolValue(current.get("fatigueTrackingEnabled"), true)
                || locked != boolValue(current.get("capacityLocked"), false))) {
            recalibrateModelInternal(userId);
        }
        return profile(userId);
    }

    @Transactional
    public Map<String, Object> resetProfile(long userId) {
        ensureProfile(userId);
        jdbc.update("update fatigue_survey set model_eligible=false,learning_weight=0,ineligible_reason='model_reset',capacity_after=null,updated_at=utc_timestamp() where user_id=? and model_eligible=true", userId);
        resetModelState(userId);
        refreshPersistedSummaries(userId);
        return profile(userId);
    }

    @Transactional
    public Map<String, Object> history(long userId, String dateFrom, String dateTo) {
        LocalDate from = parseDate(dateFrom, "dateFrom");
        LocalDate to = parseDate(dateTo, "dateTo");
        validateHistoryRange(from, to);
        ensureProfile(userId);
        ZoneId zone = userZone(userId);
        Map<LocalDate, Map<String, Object>> byDate = new LinkedHashMap<>();
        jdbc.query("select local_date localDate,timezone_snapshot timezone,planned_load plannedLoad,completed_load completedLoad,predicted_score predictedScore,pending_count pendingCount,completed_count completedCount,algorithm_version algorithmVersion,data_revision dataRevision from fatigue_daily_summary where user_id=? and local_date between ? and ? order by local_date desc",
                rs -> {
                    Map<String, Object> row = summaryRow(rs);
                    byDate.put(rs.getDate("localDate").toLocalDate(), row);
                }, userId, from, to);
        jdbc.query("select local_date localDate,score,external_factor_level externalFactorLevel,external_factor_tags externalFactorTags,model_eligible modelEligible,ineligible_reason ineligibleReason,learning_weight learningWeight,submitted_at submittedAt,updated_at updatedAt from fatigue_survey where user_id=? and local_date between ? and ? order by local_date desc",
                rs -> {
                    LocalDate date = rs.getDate("localDate").toLocalDate();
                    Map<String, Object> row = byDate.computeIfAbsent(date, ignored -> emptyHistoryRow(date, zone));
                    row.put("actualScore", rs.getInt("score"));
                    row.put("externalFactorLevel", rs.getInt("externalFactorLevel"));
                    row.put("externalFactorTags", rs.getString("externalFactorTags"));
                    row.put("modelEligible", rs.getBoolean("modelEligible"));
                    row.put("ineligibleReason", rs.getString("ineligibleReason"));
                    row.put("learningWeight", rs.getBigDecimal("learningWeight"));
                    row.put("submittedAt", iso(rs.getTimestamp("submittedAt")));
                    row.put("updatedAt", iso(rs.getTimestamp("updatedAt")));
                }, userId, from, to);
        List<Map<String, Object>> list = new ArrayList<>(byDate.values());
        list.sort(Comparator.comparing(item -> LocalDate.parse(String.valueOf(item.get("localDate"))), Comparator.reverseOrder()));
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("dateFrom", from.toString());
        result.put("dateTo", to.toString());
        result.put("timezone", zone.getId());
        result.put("profile", profileResponse(profileRow(userId)));
        result.put("list", list);
        result.put("disclaimer", DISCLAIMER);
        return result;
    }

    @Transactional
    public Map<String, Object> report(long userId, String period) {
        return report(userId, period, (String) null);
    }

    @Transactional
    public Map<String, Object> report(long userId, String period, String date) {
        String normalized = period == null || period.isBlank() ? "week" : period.trim().toLowerCase();
        if (!"week".equals(normalized) && !"month".equals(normalized)) {
            throw new BusinessException(400, "period must be 'week' or 'month'");
        }
        ensureProfile(userId);
        ZoneId zone = userZone(userId);
        LocalDate anchor = date == null || date.isBlank() ? LocalDate.now(zone) : parseDate(date, "date");
        LocalDate from;
        LocalDate to;
        if ("week".equals(normalized)) {
            from = anchor.minusDays(anchor.getDayOfWeek().getValue() - 1L);
            to = from.plusDays(6);
        } else {
            from = anchor.withDayOfMonth(1);
            to = anchor.with(java.time.temporal.TemporalAdjusters.lastDayOfMonth());
        }

        ScheduleService scheduleService = scheduleServiceProvider.getIfAvailable();
        if (scheduleService != null) scheduleService.materializeForRange(userId, from, to);

        Map<String, Object> profile = profileRow(userId);
        Map<Integer, BigDecimal> currentWeights = weights(profile);
        BigDecimal capacity = decimal(profile.get("capacity75"), DEFAULT_CAPACITY);
        boolean trackingOn = trackingEnabled(profile);

        Map<LocalDate, BigDecimal> plannedByDate = new LinkedHashMap<>();
        Map<LocalDate, BigDecimal> completedByDate = new LinkedHashMap<>();
        int personalScheduleCount = 0;
        int personalCompletedCount = 0;
        for (Map<String, Object> schedule : schedulesForUser(userId)) {
            String status = String.valueOf(schedule.getOrDefault("status", "pending"));
            LocalDate plannedDate = schedulePlannedDate(schedule, zone);
            if (plannedDate != null && !plannedDate.isBefore(from) && !plannedDate.isAfter(to) && !"cancelled".equals(status)) {
                personalScheduleCount++;
                int level = (int) number(schedule.get("fatigueLevel"), 3);
                BigDecimal weight = currentWeights.getOrDefault(level, BigDecimal.valueOf(defaultWeight(level)));
                plannedByDate.merge(plannedDate, weight, BigDecimal::add);
            }
            if ("completed".equals(status)) {
                LocalDate completedDate = localDate(schedule.get("completedAt"), zone);
                if (completedDate != null && !completedDate.isBefore(from) && !completedDate.isAfter(to)) {
                    personalCompletedCount++;
                    int completedLevel = (int) number(schedule.get("completedFatigueLevel"), number(schedule.get("fatigueLevel"), 3));
                    BigDecimal completedWeight = decimal(schedule.get("completedFatigueWeight"), BigDecimal.valueOf(defaultWeight(completedLevel)));
                    completedByDate.merge(completedDate, completedWeight, BigDecimal::add);
                }
            }
        }
        Map<LocalDate, BigDecimal> teamByDate = trackingOn ? teamCompletedLoadByDate(userId, from, to, zone) : Map.of();

        Map<LocalDate, Integer> surveyScoreByDate = new HashMap<>();
        jdbc.query("select local_date localDate,score from fatigue_survey where user_id=? and local_date between ? and ?",
                rs -> {
                    surveyScoreByDate.put(rs.getDate("localDate").toLocalDate(), rs.getInt("score"));
                },
                userId, from, to);
        Map<LocalDate, Integer> persistedPredictedByDate = new HashMap<>();
        jdbc.query("select local_date localDate,predicted_score predictedScore from fatigue_daily_summary where user_id=? and local_date between ? and ?",
                rs -> {
                    persistedPredictedByDate.put(rs.getDate("localDate").toLocalDate(), rs.getInt("predictedScore"));
                },
                userId, from, to);

        List<Map<String, Object>> trend = new ArrayList<>();
        BigDecimal sumPlanned = BigDecimal.ZERO;
        BigDecimal sumCompleted = BigDecimal.ZERO;
        BigDecimal sumTeam = BigDecimal.ZERO;
        BigDecimal peakLoad = BigDecimal.ZERO;
        LocalDate peakDay = null;
        BigDecimal surveySum = BigDecimal.ZERO;
        int surveyCount = 0;
        BigDecimal predictedSum = BigDecimal.ZERO;
        int predictedCount = 0;
        for (LocalDate day = from; !day.isAfter(to); day = day.plusDays(1)) {
            BigDecimal planned = plannedByDate.getOrDefault(day, BigDecimal.ZERO);
            BigDecimal completed = completedByDate.getOrDefault(day, BigDecimal.ZERO);
            BigDecimal team = teamByDate.getOrDefault(day, BigDecimal.ZERO);
            Integer surveyScore = surveyScoreByDate.get(day);
            Integer persistedPredicted = persistedPredictedByDate.get(day);
            int predicted = persistedPredicted != null ? persistedPredicted : predictedScore(planned, capacity);

            Map<String, Object> row = new LinkedHashMap<>();
            row.put("localDate", day.toString());
            row.put("plannedLoad", scale(planned));
            row.put("completedLoad", scale(completed));
            row.put("teamCompletedLoad", scale(team));
            row.put("totalCompletedLoad", scale(completed.add(team)));
            row.put("predictedScore", predicted);
            row.put("surveyScore", surveyScore);
            trend.add(row);

            sumPlanned = sumPlanned.add(planned);
            sumCompleted = sumCompleted.add(completed);
            sumTeam = sumTeam.add(team);
            if (planned.compareTo(peakLoad) > 0) {
                peakLoad = planned;
                peakDay = day;
            }
            if (surveyScore != null) {
                surveySum = surveySum.add(BigDecimal.valueOf(surveyScore));
                surveyCount++;
            }
            if (predicted > 0) {
                predictedSum = predictedSum.add(BigDecimal.valueOf(predicted));
                predictedCount++;
            }
        }

        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("surveyScoreAvg", surveyCount == 0 ? null : scale(surveySum.divide(BigDecimal.valueOf(surveyCount), 3, RoundingMode.HALF_UP)));
        summary.put("predictedScoreAvg", predictedCount == 0 ? null : scale(predictedSum.divide(BigDecimal.valueOf(predictedCount), 3, RoundingMode.HALF_UP)));
        LocalDate peak = peakLoad.signum() > 0 ? peakDay : null;
        summary.put("peakDay", peak == null ? null : peak.toString());
        summary.put("peakDayLoad", scale(peakLoad));
        summary.put("peakDayCompletedLoad", peak == null ? null : scale(completedByDate.getOrDefault(peak, BigDecimal.ZERO)));
        summary.put("peakDayTeamCompletedLoad", peak == null ? null : scale(teamByDate.getOrDefault(peak, BigDecimal.ZERO)));
        summary.put("peakDayTotalCompletedLoad", peak == null ? null : scale(completedByDate.getOrDefault(peak, BigDecimal.ZERO).add(teamByDate.getOrDefault(peak, BigDecimal.ZERO))));
        summary.put("personalPlannedLoad", scale(sumPlanned));
        summary.put("personalCompletedLoad", scale(sumCompleted));
        summary.put("teamCompletedLoad", scale(sumTeam));
        summary.put("totalCompletedLoad", scale(sumCompleted.add(sumTeam)));
        summary.put("personalScheduleCount", personalScheduleCount);
        summary.put("personalCompletedCount", personalCompletedCount);
        summary.put("personalCompletionRate", personalScheduleCount == 0 ? null
                : BigDecimal.valueOf(personalCompletedCount).divide(BigDecimal.valueOf(personalScheduleCount), 4, RoundingMode.HALF_UP).stripTrailingZeros());
        summary.put("personalCompletionRateNote", "完成率按完成日归属所在周期：本期实际完成数 / 本期计划数，跨周期完成不计入计划日周期。");

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("period", normalized);
        result.put("dateFrom", from.toString());
        result.put("dateTo", to.toString());
        result.put("timezone", zone.getId());
        result.put("summary", summary);
        result.put("trend", trend);
        result.put("disclaimer", DISCLAIMER);
        return result;
    }

    private Map<LocalDate, BigDecimal> teamCompletedLoadByDate(long userId, LocalDate from, LocalDate to, ZoneId zone) {
        Instant start = from.atStartOfDay(zone).toInstant();
        Instant end = to.plusDays(1).atStartOfDay(zone).toInstant();
        Map<LocalDate, BigDecimal> result = new LinkedHashMap<>();
        jdbc.query("select completed_at completedAt, completed_fatigue_weight weight from team_task_assignee where user_id=? and completed_at is not null and completed_fatigue_weight is not null and completed_at >= ? and completed_at < ?",
                rs -> {
                    LocalDate completedDate = rs.getTimestamp("completedAt").toInstant().atZone(zone).toLocalDate();
                    BigDecimal weight = rs.getBigDecimal("weight");
                    if (weight != null) result.merge(completedDate, weight, BigDecimal::add);
                },
                userId, Timestamp.from(start), Timestamp.from(end));
        return result;
    }

    @Transactional
    public String exportHistoryCsv(long userId, String dateFrom, String dateTo) {
        Map<String, Object> history = history(userId, dateFrom, dateTo);
        StringBuilder csv = new StringBuilder();
        csv.append("日期,时区,计划负荷,已完成负荷,预计分数,实际反馈,待办数,完成数,是否参与学习,排除原因,算法版本\r\n");
        Object raw = history.get("list");
        if (raw instanceof List<?> list) {
            for (Object item : list) {
                if (!(item instanceof Map<?, ?> row)) continue;
                csv.append(csvValue(row.get("localDate"))).append(',')
                        .append(csvValue(row.get("timezone"))).append(',')
                        .append(csvValue(row.get("plannedLoad"))).append(',')
                        .append(csvValue(row.get("completedLoad"))).append(',')
                        .append(csvValue(row.get("predictedScore"))).append(',')
                        .append(csvValue(row.get("actualScore"))).append(',')
                        .append(csvValue(row.get("pendingCount"))).append(',')
                        .append(csvValue(row.get("completedCount"))).append(',')
                        .append(csvValue(row.get("modelEligible"))).append(',')
                        .append(csvValue(row.get("ineligibleReason"))).append(',')
                        .append(csvValue(row.get("algorithmVersion"))).append("\r\n");
            }
        }
        return csv.toString();
    }

    @Transactional
    public Map<String, Object> deleteSurveyHistory(long userId) {
        ensureProfile(userId);
        int deleted = jdbc.update("delete from fatigue_survey where user_id=?", userId);
        jdbc.update("delete from fatigue_survey_skip where user_id=?", userId);
        resetModelState(userId);
        refreshPersistedSummaries(userId);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("deletedCount", deleted);
        result.put("profile", profile(userId));
        return result;
    }

    @Transactional
    public Map<String, Object> surveyToday(long userId) {
        return surveyToday(userId, null);
    }

    @Transactional
    public Map<String, Object> surveyToday(long userId, String requestedDate) {
        ensureProfile(userId);
        ZoneId zone = userZone(userId);
        LocalDate today = LocalDate.now(zone);
        LocalDate selected;
        if (requestedDate != null && !requestedDate.isBlank()) {
            selected = parseDate(requestedDate, "date");
            if (selected.isAfter(today) || selected.isBefore(today.minusDays(1))) {
                throw new BusinessException(400, "survey date must be today or yesterday");
            }
        } else {
            Map<String, Object> todayDaily = daily(userId, today);
            Map<String, Object> todaySurvey = surveyRow(userId, today);
            LocalDate yesterday = today.minusDays(1);
            Map<String, Object> yesterdaySurvey = surveyRow(userId, yesterday);
            Map<String, Object> yesterdayDaily = null;
            if (todaySurvey != null || number(todayDaily.get("completedCount"), 0) > 0) {
                selected = today;
            } else {
                yesterdayDaily = daily(userId, yesterday);
                selected = yesterdaySurvey != null || number(yesterdayDaily.get("completedCount"), 0) > 0 ? yesterday : today;
            }
        }

        Map<String, Object> profile = profileRow(userId);
        Map<String, Object> daily = daily(userId, selected);
        Map<String, Object> survey = surveyRow(userId, selected);
        boolean pending = surveyPending(profile, selected, daily, survey, zone);
        List<String> availableDates = new ArrayList<>();
        for (LocalDate candidate : List.of(today, today.minusDays(1))) {
            Map<String, Object> candidateSurvey = surveyRow(userId, candidate);
            Map<String, Object> candidateDaily = candidate.equals(selected) ? daily : daily(userId, candidate);
            if (candidateSurvey != null || number(candidateDaily.get("completedCount"), 0) > 0) {
                availableDates.add(candidate.toString());
            }
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("localDate", selected.toString());
        result.put("timezone", zone.getId());
        result.put("pending", pending);
        result.put("isBackfill", selected.isBefore(today));
        result.put("availableDates", availableDates);
        result.put("canSnooze", pending && selected.equals(today));
        result.put("canSkip", pending);
        result.put("survey", survey == null ? Map.of() : survey);
        result.put("daily", daily);
        result.put("profile", profileResponse(profile));
        result.put("comparison", survey == null ? Map.of() : surveyComparison(daily, survey, profile));
        result.put("recurringExternalFactors", recurringExternalFactors(userId, selected));
        result.put("disclaimer", DISCLAIMER);
        return result;
    }

    @Transactional
    public Map<String, Object> submitSurvey(long userId, Map<String, Object> req) {
        ensureProfile(userId);
        Map<String, Object> profile = profileRow(userId);
        if (!fatigueFeatureEnabled || !fatigueSurveysFeatureEnabled
                || !boolValue(profile.get("surveyEnabled"), true) || !trackingEnabled(profile)) {
            throw new BusinessException(400, "fatigue survey is disabled");
        }
        ZoneId zone = userZone(userId);
        LocalDate localDate = req.containsKey("localDate")
                ? parseDate(String.valueOf(req.get("localDate")), "localDate")
                : LocalDate.now(zone);
        LocalDate today = LocalDate.now(zone);
        if (localDate.isAfter(today)) throw new BusinessException(400, "localDate cannot be in the future");
        if (localDate.isBefore(today.minusDays(Math.max(30, retentionDays)))) {
            throw new BusinessException(400, "survey is outside the retention period");
        }
        int score = strictInt(req, "score");
        if (score < 0 || score > 100) throw new BusinessException(400, "score must be between 0 and 100");
        int externalLevel = req.containsKey("externalFactorLevel") ? strictInt(req, "externalFactorLevel") : 0;
        if (externalLevel < 0 || externalLevel > 2) throw new BusinessException(400, "externalFactorLevel is invalid");
        List<String> tags = externalLevel == 0 ? List.of() : stringList(req.get("externalFactorTags"));
        if (tags.size() > 8 || tags.stream().anyMatch(tag -> tag.length() > 40)) {
            throw new BusinessException(400, "externalFactorTags is invalid");
        }

        Map<String, Object> existing = surveyRow(userId, localDate);
        Map<String, Object> daily = daily(userId, localDate);
        BigDecimal completedLoad = decimal(daily.get("completedLoad"));
        Map<String, Integer> completedLevelCounts = levelCounts(daily.get("completedLevelCounts"));
        boolean delayed = Instant.now().isAfter(localDate.atTime(DEFAULT_SURVEY_HOUR, DEFAULT_SURVEY_MINUTE)
                .atZone(zone).toInstant().plus(Duration.ofHours(48)));
        boolean timezoneChanged = existing != null && "timezone_changed".equals(existing.get("ineligibleReason"));
        boolean strongFactor = externalLevel == 2 || tags.stream().anyMatch(STRONG_FACTOR_TAGS::contains);
        boolean eligible = completedLoad.signum() > 0 && !delayed && !timezoneChanged && !strongFactor;
        String ineligibleReason = null;
        if (completedLoad.signum() <= 0) ineligibleReason = "no_completed_personal_schedule";
        else if (delayed) ineligibleReason = "submitted_after_48_hours";
        else if (timezoneChanged) ineligibleReason = "timezone_changed";
        else if (strongFactor) ineligibleReason = "external_factor";
        BigDecimal learningWeight = eligible
                ? (externalLevel == 1 ? BigDecimal.valueOf(.5) : BigDecimal.ONE)
                : BigDecimal.ZERO;
        BigDecimal capacityBefore = decimal(profile.get("capacity75"), DEFAULT_CAPACITY);
        String tagsJson = json(tags);
        String weightsJson = json(weights(profile));
        String countsJson = json(completedLevelCounts);

        String updateSql = "update fatigue_survey set timezone_snapshot=?,score=?,external_factor_level=?,external_factor_tags=?,completed_load_snapshot=?,weights_snapshot=?,capacity_before=?,capacity_after=null,model_eligible=?,ineligible_reason=?,learning_weight=?,completed_level_counts=?,algorithm_version=?,updated_at=utc_timestamp() where user_id=? and local_date=?";
        Object[] updateArgs = {zone.getId(), score, externalLevel, tagsJson, completedLoad, weightsJson, capacityBefore,
                eligible, ineligibleReason, learningWeight, countsJson, ALGORITHM_VERSION, userId, localDate};
        if (existing == null) {
            try {
                jdbc.update("insert into fatigue_survey (user_id,local_date,timezone_snapshot,score,external_factor_level,external_factor_tags,completed_load_snapshot,weights_snapshot,capacity_before,model_eligible,ineligible_reason,learning_weight,completed_level_counts,algorithm_version) values (?,?,?,?,?,?,?,?,?,?,?,?,?,?)",
                        userId, localDate, zone.getId(), score, externalLevel, tagsJson, completedLoad, weightsJson,
                        capacityBefore, eligible, ineligibleReason, learningWeight, countsJson, ALGORITHM_VERSION);
            } catch (DataIntegrityViolationException concurrentInsert) {
                jdbc.update(updateSql, updateArgs);
            }
        } else {
            jdbc.update(updateSql, updateArgs);
        }
        jdbc.update("delete from fatigue_survey_skip where user_id=? and local_date=?", userId, localDate);
        jdbc.update("update user_fatigue_profile set survey_snoozed_until=null,survey_skipped_date=case when survey_skipped_date=? then null else survey_skipped_date end,updated_at=utc_timestamp() where user_id=?",
                localDate, userId);
        recalibrateModelInternal(userId);
        Map<String, Object> updatedProfile = profileRow(userId);
        Map<String, Object> updatedDaily = daily(userId, localDate);
        Map<String, Object> updatedSurvey = surveyRow(userId, localDate);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("localDate", localDate.toString());
        result.put("survey", updatedSurvey);
        result.put("daily", updatedDaily);
        result.put("profile", profileResponse(updatedProfile));
        result.put("comparison", surveyComparison(updatedDaily, updatedSurvey, updatedProfile));
        result.put("recurringExternalFactors", recurringExternalFactors(userId, localDate));
        result.put("disclaimer", DISCLAIMER);
        return result;
    }

    @Transactional
    public Map<String, Object> snoozeSurvey(long userId, Map<String, Object> req) {
        ensureProfile(userId);
        int minutes = req != null && req.containsKey("minutes") ? strictInt(req, "minutes") : 30;
        if (minutes < 5 || minutes > 1440) throw new BusinessException(400, "minutes must be between 5 and 1440");
        Instant until = Instant.now().plus(Duration.ofMinutes(minutes));
        jdbc.update("update user_fatigue_profile set survey_snoozed_until=?,updated_at=utc_timestamp() where user_id=?",
                Timestamp.from(until), userId);
        return surveyToday(userId);
    }

    @Transactional
    public Map<String, Object> skipSurvey(long userId, Map<String, Object> req) {
        ensureProfile(userId);
        ZoneId zone = userZone(userId);
        LocalDate today = LocalDate.now(zone);
        LocalDate date = req != null && req.get("localDate") != null
                ? parseDate(String.valueOf(req.get("localDate")), "localDate") : today;
        if (date.isAfter(today) || date.isBefore(today.minusDays(1))) {
            throw new BusinessException(400, "survey date must be today or yesterday");
        }
        try {
            jdbc.update("insert into fatigue_survey_skip (user_id,local_date) values (?,?)", userId, date);
        } catch (DataIntegrityViolationException duplicateSkip) {
            jdbc.update("update fatigue_survey_skip set updated_at=utc_timestamp() where user_id=? and local_date=?", userId, date);
        }
        jdbc.update("update user_fatigue_profile set survey_skipped_date=?,survey_snoozed_until=null,updated_at=utc_timestamp() where user_id=?",
                date, userId);
        return surveyToday(userId, date.toString());
    }

    @Transactional
    public Map<String, Object> suppressAlertsToday(long userId, Map<String, Object> req) {
        ensureProfile(userId);
        ZoneId zone = userZone(userId);
        LocalDate today = LocalDate.now(zone);
        Map<String, Object> daily = daily(userId, today);
        String requestedBand = req == null ? "" : Objects.toString(req.get("thresholdBand"), "");
        String band = alertSeverity(requestedBand) > 0 ? requestedBand : highestBand(
                alertBand((int) number(daily.get("predictedScore"), 0)),
                alertBand((int) number(daily.get("actualLoadScore"), 0)));
        if (band == null) band = "tired";
        jdbc.update("update user_fatigue_profile set alert_suppressed_date=?,alert_suppressed_band=?,updated_at=utc_timestamp() where user_id=?",
                today, band, userId);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("localDate", today.toString());
        result.put("thresholdBand", band);
        result.put("profile", profile(userId));
        return result;
    }

    @Transactional
    public Map<String, Object> preview(long userId, Map<String, Object> req) {
        ensureProfile(userId);
        ZoneId zone = userZone(userId);
        Map<String, Object> profile = profileRow(userId);
        String operation = Objects.toString(req.getOrDefault("operation", "create"), "create");
        if (!List.of("create", "edit", "delete", "cancel").contains(operation)) {
            throw new BusinessException(400, "operation is invalid");
        }
        Map<String, Object> existing = null;
        if (!"create".equals(operation)) {
            long scheduleId = requiredLong(req, "scheduleId");
            existing = scheduleForUser(scheduleId, userId);
            if (existing == null) throw new BusinessException(404, "schedule not found");
        }
        Map<String, Object> proposed = previewSchedule(existing, req, operation);
        LocalDate oldDate = existing == null ? null : schedulePlannedDate(existing, zone);
        LocalDate proposedDate = proposed == null ? null : schedulePlannedDate(proposed, zone);
        if (proposedDate == null && req.get("localDate") != null) {
            proposedDate = parseDate(String.valueOf(req.get("localDate")), "localDate");
        }
        LinkedHashSet<LocalDate> dates = new LinkedHashSet<>();
        if (oldDate != null) dates.add(oldDate);
        if (proposedDate != null) dates.add(proposedDate);
        if (dates.isEmpty()) dates.add(LocalDate.now(zone));

        List<Map<String, Object>> previews = new ArrayList<>();
        for (LocalDate date : dates) {
            Map<String, Object> beforeDaily = trackingEnabled(profile)
                    ? dailyResult(userId, date, zone, profile, number(profile.get("dataRevision"), 0L), false)
                    : disabledDaily(userId, date, zone, profile);
            BigDecimal afterLoad = decimal(beforeDaily.get("plannedLoad"));
            BigDecimal removedWeight = BigDecimal.ZERO;
            BigDecimal addedWeight = BigDecimal.ZERO;
            if (existing != null && !"cancelled".equals(existing.get("status")) && date.equals(oldDate)) {
                removedWeight = weightFor(profile, (int) number(existing.get("fatigueLevel"), 3));
                afterLoad = afterLoad.subtract(removedWeight);
            }
            if (proposed != null && !"cancelled".equals(proposed.get("status")) && date.equals(proposedDate)) {
                addedWeight = weightFor(profile, (int) number(proposed.get("fatigueLevel"), 3));
                afterLoad = afterLoad.add(addedWeight);
            }
            afterLoad = afterLoad.max(BigDecimal.ZERO);
            Map<String, Object> before = fatigueProjection(beforeDaily);
            Map<String, Object> after = projection(afterLoad, decimal(profile.get("capacity75"), DEFAULT_CAPACITY));
            int beforeScore = (int) number(before.get("predictedScore"), 0);
            int afterScore = (int) number(after.get("predictedScore"), 0);
            boolean shouldWarn = afterScore >= 70 && afterScore > beforeScore;
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("localDate", date.toString());
            row.put("before", before);
            row.put("after", after);
            row.put("removedWeight", scale(removedWeight));
            row.put("addedWeight", scale(addedWeight));
            row.put("deltaScore", afterScore - beforeScore);
            row.put("shouldWarn", shouldWarn);
            row.put("warning", shouldWarn ? previewWarning(date, afterScore) : "");
            row.put("topContributors", previewContributors(beforeDaily, existing, proposed, date, oldDate, proposedDate, profile));
            previews.add(row);
        }
        Map<String, Object> first = previews.get(0);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("localDate", first.get("localDate"));
        result.put("before", first.get("before"));
        result.put("after", first.get("after"));
        result.put("removedWeight", first.get("removedWeight"));
        result.put("affectedDates", previews.stream().map(item -> item.get("localDate")).toList());
        result.put("dates", previews);
        result.put("shouldWarn", previews.stream().anyMatch(item -> boolValue(item.get("shouldWarn"), false)));
        result.put("disclaimer", DISCLAIMER);
        return result;
    }

    @Transactional
    public int scanSurveyPrompts() {
        if (!fatigueFeatureEnabled || !fatigueSurveysFeatureEnabled) return 0;
        List<Long> userIds = jdbc.queryForList("select p.user_id from user_fatigue_profile p join `user` u on u.id=p.user_id and u.deleted_at is null where p.survey_enabled=true and p.fatigue_tracking_enabled=true", Long.class);
        int created = 0;
        Instant now = Instant.now();
        for (Long userId : userIds) {
            ZoneId zone = userZone(userId);
            LocalDate date = now.atZone(zone).toLocalDate();
            LocalTime localNow = now.atZone(zone).toLocalTime();
            Map<String, Object> profile = profileRow(userId);
            if (!trackingEnabled(profile) || surveySkipped(profile, date) || surveySnoozed(profile, now)) continue;
            LocalTime surveyTime = parseSurveyTime(profile.get("surveyTime"));
            if (localNow.isBefore(surveyTime) || isQuietTime(userId, localNow)) continue;
            Map<String, Object> daily = daily(userId, date);
            if (number(daily.get("completedCount"), 0) <= 0 || surveyRow(userId, date) != null) continue;
            if (notificationService.hasNotificationForDate(userId, "fatigue_survey", date)) continue;
            if (!claimSurveyPrompt(userId, date)) continue;
            boolean sent = notificationService.createNotification(userId, "fatigue_survey", "填写今日疲劳调查",
                    "今天这些个人日程给你带来的疲劳程度是多少？", "fatigue", null, null,
                    date, "/fatigue/survey", number(profile.get("dataRevision"), 0L));
            if (sent) created++;
            else jdbc.update("delete from fatigue_survey_prompt_log where user_id=? and local_date=?", userId, date);
        }
        return created;
    }

    @Transactional
    public int scanFatigueAlerts() {
        if (!fatigueFeatureEnabled || !fatigueAlertsFeatureEnabled) return 0;
        List<Long> userIds = jdbc.queryForList("select p.user_id from user_fatigue_profile p join `user` u on u.id=p.user_id and u.deleted_at is null where p.fatigue_alert_enabled=true and p.fatigue_tracking_enabled=true", Long.class);
        int created = 0;
        Instant now = Instant.now();
        for (Long userId : userIds) {
            ZoneId zone = userZone(userId);
            LocalDate date = now.atZone(zone).toLocalDate();
            LocalTime localTime = now.atZone(zone).toLocalTime();
            Map<String, Object> profile = profileRow(userId);
            if (!trackingEnabled(profile) || isQuietTime(userId, localTime)) continue;
            Map<String, Object> daily = daily(userId, date);
            int actualScore = (int) number(daily.get("actualLoadScore"), 0);
            if (createFatigueAlert(userId, date, "actual", actualScore, daily, profile, now)) created++;
            int planScore = (int) number(daily.get("predictedScore"), 0);
            if (createFatigueAlert(userId, date, "plan", planScore, daily, profile, now)) created++;
        }
        return created;
    }

    @Transactional
    public int cleanupRetention() {
        int safeRetentionDays = Math.max(30, retentionDays);
        LocalDate cutoff = LocalDate.now(ZoneOffset.UTC).minusDays(safeRetentionDays);
        List<Long> affectedUsers = jdbc.queryForList("select distinct user_id from fatigue_survey where local_date<?", Long.class, cutoff);
        int deleted = jdbc.update("delete from fatigue_survey where local_date<?", cutoff);
        deleted += jdbc.update("delete from fatigue_survey_skip where local_date<?", cutoff);
        deleted += jdbc.update("delete from fatigue_survey_prompt_log where local_date<?", cutoff);
        deleted += jdbc.update("delete from fatigue_daily_summary where local_date<?", cutoff);
        deleted += jdbc.update("delete from fatigue_alert_log where local_date<?", cutoff);
        for (Long userId : affectedUsers) {
            Integer active = jdbc.queryForObject("select count(*) from `user` where id=? and deleted_at is null", Integer.class, userId);
            if (active != null && active > 0) recalibrateModelInternal(userId);
        }
        return deleted;
    }

    @Transactional
    public Map<String, Object> recalibrateModel(long userId) {
        ensureProfile(userId);
        return recalibrateModelInternal(userId);
    }

    private Map<String, Object> recalibrateModelInternal(long userId) {
        Map<String, Object> profile = profileRow(userId);
        List<FatigueModelCalibrator.Sample> samples = jdbc.query(
                "select local_date localDate,score,completed_load_snapshot completedLoad,learning_weight learningWeight,completed_level_counts completedLevelCounts from fatigue_survey where user_id=? and model_eligible=true and learning_weight>0 order by local_date",
                (rs, i) -> new FatigueModelCalibrator.Sample(
                        rs.getDate("localDate").toLocalDate(),
                        rs.getInt("score"),
                        decimal(rs.getBigDecimal("completedLoad")),
                        decimal(rs.getBigDecimal("learningWeight"), BigDecimal.ONE),
                        levelCountArray(rs.getString("completedLevelCounts"))),
                userId);
        Timestamp lastWeightCalibrated = timestamp(profile.get("lastWeightCalibratedAtRaw"));
        boolean weightFitDue = lastWeightCalibrated == null
                || lastWeightCalibrated.toInstant().isBefore(Instant.now().minus(Duration.ofDays(7)));
        boolean learningEnabled = trackingEnabled(profile) && fatigueLearningFeatureEnabled;
        Map<Integer, BigDecimal> currentWeights = weights(profile);
        FatigueModelCalibrator.Result calibrated = FatigueModelCalibrator.calibrate(
                samples,
                decimal(profile.get("capacity75"), DEFAULT_CAPACITY),
                currentWeights,
                boolValue(profile.get("capacityLocked"), false),
                learningEnabled,
                weightFitDue);

        jdbc.update("update fatigue_survey set capacity_after=null where user_id=?", userId);
        for (Map.Entry<LocalDate, BigDecimal> entry : calibrated.capacityAfter().entrySet()) {
            jdbc.update("update fatigue_survey set capacity_after=? where user_id=? and local_date=?",
                    entry.getValue(), userId, entry.getKey());
        }
        Instant now = Instant.now();
        Timestamp lastCalibrated = learningEnabled && samples.size() >= 7 ? Timestamp.from(now) : null;
        boolean weightsChanged = !sameWeights(currentWeights, calibrated.weights());
        boolean coefficientsChanged = weightsChanged
                || decimal(profile.get("capacity75"), DEFAULT_CAPACITY).compareTo(calibrated.capacity()) != 0;
        Timestamp nextWeightCalibrated = lastWeightCalibrated;
        if (calibrated.weightsAdjusted()) nextWeightCalibrated = Timestamp.from(now);
        else if (weightsChanged && samples.size() < 30) nextWeightCalibrated = null;
        jdbc.update("update user_fatigue_profile set level_1_weight=?,level_2_weight=?,level_3_weight=?,level_4_weight=?,level_5_weight=?,capacity_75=?,model_stage=?,valid_survey_days=?,algorithm_version=?,last_calibrated_at=?,last_weight_calibrated_at=?,data_revision=data_revision+1,updated_at=utc_timestamp() where user_id=?",
                calibrated.weights().get(1), calibrated.weights().get(2), calibrated.weights().get(3),
                calibrated.weights().get(4), calibrated.weights().get(5), calibrated.capacity(),
                calibrated.stage(), samples.size(), ALGORITHM_VERSION, lastCalibrated, nextWeightCalibrated, userId);
        if (coefficientsChanged) refreshPersistedSummaries(userId);
        return profileResponse(profileRow(userId));
    }

    private Map<String, Object> dailyResult(long userId, LocalDate date, ZoneId zone,
                                            Map<String, Object> profile, long revision, boolean persist) {
        List<Map<String, Object>> schedules = schedulesForUser(userId);
        Map<Integer, BigDecimal> currentWeights = weights(profile);
        BigDecimal planned = BigDecimal.ZERO;
        BigDecimal completed = BigDecimal.ZERO;
        int pendingCount = 0;
        int completedCount = 0;
        int[] completedCounts = new int[5];
        List<Map<String, Object>> contributors = new ArrayList<>();
        List<Map<String, Object>> completedContributors = new ArrayList<>();
        for (Map<String, Object> schedule : schedules) {
            LocalDate plannedDate = schedulePlannedDate(schedule, zone);
            String status = String.valueOf(schedule.getOrDefault("status", "pending"));
            if (!"cancelled".equals(status) && date.equals(plannedDate)) {
                int level = (int) number(schedule.get("fatigueLevel"), 3);
                BigDecimal weight = currentWeights.getOrDefault(level, BigDecimal.valueOf(defaultWeight(level)));
                planned = planned.add(weight);
                if ("pending".equals(status)) pendingCount++;
                Map<String, Object> contributor = new LinkedHashMap<>();
                contributor.put("scheduleId", number(schedule.get("id"), 0L));
                contributor.put("title", schedule.get("title"));
                contributor.put("urgencyLevel", number(schedule.get("urgencyLevel"), 3));
                contributor.put("fatigueLevel", level);
                contributor.put("weight", scale(weight));
                contributors.add(contributor);
            }
            if ("completed".equals(status) && date.equals(localDate(schedule.get("completedAt"), zone))) {
                completedCount++;
                int completedLevel = (int) number(schedule.get("completedFatigueLevel"), number(schedule.get("fatigueLevel"), 3));
                BigDecimal completedWeight = decimal(schedule.get("completedFatigueWeight"), BigDecimal.valueOf(defaultWeight(completedLevel)));
                completed = completed.add(completedWeight);
                if (completedLevel >= 1 && completedLevel <= 5) completedCounts[completedLevel - 1]++;
                Map<String, Object> contributor = new LinkedHashMap<>();
                contributor.put("scheduleId", number(schedule.get("id"), 0L));
                contributor.put("title", schedule.get("title"));
                contributor.put("fatigueLevel", completedLevel);
                contributor.put("weight", scale(completedWeight));
                completedContributors.add(contributor);
            }
        }
        contributors.sort(Comparator.comparing((Map<String, Object> item) -> decimal(item.get("weight"))).reversed()
                .thenComparing(item -> String.valueOf(item.get("title"))));
        completedContributors.sort(Comparator.comparing((Map<String, Object> item) -> decimal(item.get("weight"))).reversed()
                .thenComparing(item -> String.valueOf(item.get("title"))));
        BigDecimal capacity = decimal(profile.get("capacity75"), DEFAULT_CAPACITY);
        int predicted = predictedScore(planned, capacity);
        int actualLoadScore = predictedScore(completed, capacity);
        BigDecimal teamCompleted = teamCompletedLoad(userId, date, zone);
        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("localDate", date.toString());
        summary.put("timezone", zone.getId());
        summary.put("plannedLoad", scale(planned));
        summary.put("completedLoad", scale(completed));
        summary.put("teamCompletedLoad", scale(teamCompleted));
        summary.put("totalCompletedLoad", scale(completed.add(teamCompleted)));
        summary.put("predictedScore", predicted);
        summary.put("pendingCount", pendingCount);
        summary.put("completedCount", completedCount);
        summary.put("algorithmVersion", ALGORITHM_VERSION);
        summary.put("dataRevision", revision);
        if (persist && trackingEnabled(profile)) upsertSummary(userId, date, summary);
        Map<String, Object> result = new LinkedHashMap<>(summary);
        result.put("capacity75", scale(capacity));
        result.put("level", thresholdBand(predicted));
        result.put("actualLoadScore", actualLoadScore);
        result.put("actualLevel", thresholdBand(actualLoadScore));
        result.put("modelStage", profile.get("modelStage"));
        result.put("confidence", confidence(profile));
        result.put("trackingEnabled", true);
        result.put("featureEnabled", featureEnabledFor(userId));
        result.put("completedLevelCounts", levelCountMap(completedCounts));
        result.put("topContributors", contributors.stream().limit(5).toList());
        result.put("topCompletedContributors", completedContributors.stream().limit(5).toList());
        Map<String, Object> survey = surveyRow(userId, date);
        result.put("survey", survey == null ? Map.of() : survey);
        result.put("disclaimer", DISCLAIMER);
        return result;
    }

    private Map<String, Object> disabledDaily(long userId, LocalDate date, ZoneId zone, Map<String, Object> profile) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("localDate", date.toString());
        result.put("timezone", zone.getId());
        result.put("plannedLoad", BigDecimal.ZERO);
        result.put("completedLoad", BigDecimal.ZERO);
        result.put("teamCompletedLoad", BigDecimal.ZERO);
        result.put("totalCompletedLoad", BigDecimal.ZERO);
        result.put("predictedScore", 0);
        result.put("actualLoadScore", 0);
        result.put("pendingCount", 0);
        result.put("completedCount", 0);
        result.put("algorithmVersion", ALGORITHM_VERSION);
        result.put("dataRevision", number(profile.get("dataRevision"), 0L));
        result.put("capacity75", scale(decimal(profile.get("capacity75"), DEFAULT_CAPACITY)));
        result.put("level", "comfortable");
        result.put("actualLevel", "comfortable");
        result.put("modelStage", profile.get("modelStage"));
        result.put("confidence", confidence(profile));
        result.put("trackingEnabled", false);
        result.put("featureEnabled", featureEnabledFor(userId));
        result.put("completedLevelCounts", levelCountMap(new int[5]));
        result.put("topContributors", List.of());
        result.put("topCompletedContributors", List.of());
        Map<String, Object> survey = surveyRow(userId, date);
        result.put("survey", survey == null ? Map.of() : survey);
        result.put("disclaimer", DISCLAIMER);
        return result;
    }

    private BigDecimal teamCompletedLoad(long userId, LocalDate date, ZoneId zone) {
        Instant start = date.atStartOfDay(zone).toInstant();
        Instant end = date.plusDays(1).atStartOfDay(zone).toInstant();
        List<Map<String, Object>> rows = jdbc.query(
                "select completed_at completedAt, completed_fatigue_weight completedFatigueWeight " +
                        "from team_task_assignee where user_id=? and completed_at is not null and completed_fatigue_weight is not null " +
                        "and completed_at >= ? and completed_at < ?",
                (rs, i) -> {
                    Map<String, Object> row = new HashMap<>();
                    row.put("completedAt", rs.getTimestamp("completedAt"));
                    row.put("weight", rs.getBigDecimal("completedFatigueWeight"));
                    return row;
                },
                userId, Timestamp.from(start), Timestamp.from(end));
        BigDecimal total = BigDecimal.ZERO;
        for (Map<String, Object> row : rows) {
            LocalDate completedDate = ((Timestamp) row.get("completedAt")).toInstant().atZone(zone).toLocalDate();
            if (date.equals(completedDate) && row.get("weight") != null) {
                total = total.add((BigDecimal) row.get("weight"));
            }
        }
        return total;
    }

    private boolean createFatigueAlert(long userId, LocalDate date, String sourceType, int score,
                                       Map<String, Object> daily, Map<String, Object> profile, Instant now) {
        String band = alertBand(score);
        if (band == null || alertSuppressed(profile, date, band)) return false;
        jdbc.queryForObject("select data_revision from user_fatigue_profile where user_id=? for update", Long.class, userId);
        Timestamp latest = jdbc.queryForObject("select max(notified_at) from fatigue_alert_log where user_id=?", Timestamp.class, userId);
        if (latest != null && latest.toInstant().isAfter(now.minus(Duration.ofHours(2)))) return false;
        try {
            jdbc.update("insert into fatigue_alert_log (user_id,local_date,source_type,threshold_band,score_snapshot,algorithm_version) values (?,?,?,?,?,?)",
                    userId, date, sourceType, band, score, ALGORITHM_VERSION);
        } catch (DataIntegrityViolationException ignored) {
            return false;
        }
        String type = "actual".equals(sourceType) ? "fatigue_actual_warning" : "fatigue_plan_warning";
        String title = "actual".equals(sourceType) ? "已完成日程负荷提醒" : "个人日程预计负荷提醒";
        String baseContent = "actual".equals(sourceType)
                ? "你今天已完成的个人日程累计负荷约为 " + score + " 分，可以考虑休息一会儿再继续。"
                : "今天的个人日程预计负荷约为 " + score + " 分，建议检查安排并预留休息时间。";
        String contributorText = contributorText(daily.get("actual".equals(sourceType)
                ? "topCompletedContributors" : "topContributors"));
        String content = baseContent
                + (contributorText.isBlank() ? "" : "主要来源：" + contributorText + "。")
                + "模型阶段：" + modelStageLabel(Objects.toString(profile.get("modelStage"), "default"))
                + "，算法 v" + ALGORITHM_VERSION + "。";
        boolean sent = notificationService.createNotification(userId, type, title, content,
                "fatigue", null, null, date, "actual".equals(sourceType) ? "/fatigue/survey" : "/schedules",
                number(daily.get("dataRevision"), 0L));
        if (!sent) {
            jdbc.update("delete from fatigue_alert_log where user_id=? and local_date=? and source_type=? and threshold_band=? and algorithm_version=?",
                    userId, date, sourceType, band, ALGORITHM_VERSION);
        }
        return sent;
    }

    private boolean surveyPending(Map<String, Object> profile, LocalDate date, Map<String, Object> daily,
                                  Map<String, Object> survey, ZoneId zone) {
        return survey == null
                && trackingEnabled(profile)
                && fatigueSurveysFeatureEnabled
                && boolValue(profile.get("surveyEnabled"), true)
                && number(daily.get("completedCount"), 0) > 0
                && !surveySkipped(profile, date)
                && !surveySnoozed(profile, Instant.now())
                && !date.isAfter(LocalDate.now(zone));
    }

    private boolean surveySkipped(Map<String, Object> profile, LocalDate date) {
        if (date.toString().equals(Objects.toString(profile.get("surveySkippedDate"), ""))) return true;
        Integer count = jdbc.queryForObject("select count(*) from fatigue_survey_skip where user_id=? and local_date=?",
                Integer.class, number(profile.get("userId"), 0L), date);
        return count != null && count > 0;
    }

    private boolean claimSurveyPrompt(long userId, LocalDate date) {
        try {
            jdbc.update("insert into fatigue_survey_prompt_log (user_id,local_date,algorithm_version) values (?,?,?)",
                    userId, date, ALGORITHM_VERSION);
            return true;
        } catch (DataIntegrityViolationException duplicatePrompt) {
            return false;
        }
    }

    private boolean surveySnoozed(Map<String, Object> profile, Instant now) {
        Timestamp snoozedUntil = timestamp(profile.get("surveySnoozedUntilRaw"));
        return snoozedUntil != null && snoozedUntil.toInstant().isAfter(now);
    }

    private boolean alertSuppressed(Map<String, Object> profile, LocalDate date, String band) {
        if (!date.toString().equals(Objects.toString(profile.get("alertSuppressedDate"), ""))) return false;
        return alertSeverity(band) <= alertSeverity(Objects.toString(profile.get("alertSuppressedBand"), ""));
    }

    private int invalidateSurveysForDates(long userId, List<LocalDate> dates) {
        int updated = 0;
        for (LocalDate date : dates) {
            updated += jdbc.update("update fatigue_survey set model_eligible=false,learning_weight=0,ineligible_reason='schedule_data_changed',capacity_after=null,updated_at=utc_timestamp() where user_id=? and local_date=? and model_eligible=true",
                    userId, date);
        }
        return updated;
    }

    private void resetModelState(long userId) {
        jdbc.update("delete from fatigue_survey_skip where user_id=?", userId);
        jdbc.update("update user_fatigue_profile set level_1_weight=1,level_2_weight=2,level_3_weight=3,level_4_weight=5,level_5_weight=8,capacity_75=18,model_stage='default',valid_survey_days=0,algorithm_version=?,capacity_locked=false,last_calibrated_at=null,last_weight_calibrated_at=null,survey_snoozed_until=null,survey_skipped_date=null,alert_suppressed_date=null,alert_suppressed_band=null,data_revision=data_revision+1,updated_at=utc_timestamp() where user_id=?",
                ALGORITHM_VERSION, userId);
    }

    private Map<String, Object> profileResponse(Map<String, Object> row) {
        Map<String, Object> result = new LinkedHashMap<>(row);
        result.remove("lastWeightCalibratedAtRaw");
        result.remove("surveySnoozedUntilRaw");
        Map<String, Object> weightMap = new LinkedHashMap<>();
        for (int level = 1; level <= 5; level++) weightMap.put(String.valueOf(level), row.get("level" + level + "Weight"));
        result.put("weights", weightMap);
        result.put("confidence", confidence(row));
        long userId = number(row.get("userId"), 0L);
        boolean featureEnabled = featureEnabledFor(userId);
        result.put("featureEnabled", featureEnabled);
        result.put("alertsFeatureEnabled", featureEnabled && fatigueAlertsFeatureEnabled);
        result.put("surveysFeatureEnabled", featureEnabled && fatigueSurveysFeatureEnabled);
        result.put("learningFeatureEnabled", featureEnabled && fatigueLearningFeatureEnabled);
        result.put("retentionDays", Math.max(30, retentionDays));
        result.put("disclaimer", DISCLAIMER);
        return result;
    }

    private Map<String, Object> profileRow(long userId) {
        try {
            return jdbc.queryForObject("select user_id userId,level_1_weight level1Weight,level_2_weight level2Weight,level_3_weight level3Weight,level_4_weight level4Weight,level_5_weight level5Weight,capacity_75 capacity75,model_stage modelStage,valid_survey_days validSurveyDays,algorithm_version algorithmVersion,fatigue_tracking_enabled fatigueTrackingEnabled,fatigue_alert_enabled fatigueAlertEnabled,survey_enabled surveyEnabled,survey_time surveyTime,capacity_locked capacityLocked,data_revision dataRevision,last_calibrated_at lastCalibratedAt,last_weight_calibrated_at lastWeightCalibratedAt,survey_snoozed_until surveySnoozedUntil,survey_skipped_date surveySkippedDate,alert_suppressed_date alertSuppressedDate,alert_suppressed_band alertSuppressedBand from user_fatigue_profile where user_id=?",
                    (rs, i) -> {
                        Map<String, Object> row = new LinkedHashMap<>();
                        row.put("userId", rs.getLong("userId"));
                        for (int level = 1; level <= 5; level++) row.put("level" + level + "Weight", rs.getBigDecimal("level" + level + "Weight"));
                        row.put("capacity75", rs.getBigDecimal("capacity75"));
                        row.put("modelStage", rs.getString("modelStage"));
                        row.put("validSurveyDays", rs.getInt("validSurveyDays"));
                        row.put("algorithmVersion", rs.getInt("algorithmVersion"));
                        row.put("fatigueTrackingEnabled", rs.getBoolean("fatigueTrackingEnabled"));
                        row.put("fatigueAlertEnabled", rs.getBoolean("fatigueAlertEnabled"));
                        row.put("surveyEnabled", rs.getBoolean("surveyEnabled"));
                        Time surveyTime = rs.getTime("surveyTime");
                        row.put("surveyTime", surveyTime == null ? "21:30:00" : surveyTime.toString());
                        row.put("capacityLocked", rs.getBoolean("capacityLocked"));
                        row.put("dataRevision", rs.getLong("dataRevision"));
                        Timestamp lastCalibrated = rs.getTimestamp("lastCalibratedAt");
                        Timestamp lastWeight = rs.getTimestamp("lastWeightCalibratedAt");
                        Timestamp snoozed = rs.getTimestamp("surveySnoozedUntil");
                        row.put("lastCalibratedAt", iso(lastCalibrated));
                        row.put("lastWeightCalibratedAt", iso(lastWeight));
                        row.put("lastWeightCalibratedAtRaw", lastWeight);
                        row.put("surveySnoozedUntil", iso(snoozed));
                        row.put("surveySnoozedUntilRaw", snoozed);
                        row.put("surveySkippedDate", dateString(rs.getDate("surveySkippedDate")));
                        row.put("alertSuppressedDate", dateString(rs.getDate("alertSuppressedDate")));
                        row.put("alertSuppressedBand", Objects.toString(rs.getString("alertSuppressedBand"), ""));
                        return row;
                    }, userId);
        } catch (EmptyResultDataAccessException ex) {
            throw new BusinessException(404, "fatigue profile not found");
        }
    }

    private void ensureProfile(long userId) {
        Integer users = jdbc.queryForObject("select count(*) from `user` where id=? and deleted_at is null", Integer.class, userId);
        if (users == null || users == 0) throw new BusinessException(404, "user not found");
        Integer profiles = jdbc.queryForObject("select count(*) from user_fatigue_profile where user_id=?", Integer.class, userId);
        if (profiles == null || profiles == 0) {
            try {
                jdbc.update("insert into user_fatigue_profile (user_id,algorithm_version) values (?,?)", userId, ALGORITHM_VERSION);
            } catch (DataIntegrityViolationException ignored) {
                // Another request initialized the same profile concurrently.
            }
        }
        jdbc.update("update user_fatigue_profile set algorithm_version=? where user_id=? and algorithm_version<?",
                ALGORITHM_VERSION, userId, ALGORITHM_VERSION);
    }

    private List<Map<String, Object>> schedulesForUser(long userId) {
        return jdbc.query("select id,title,time_type timeType,start_time startTime,end_time endTime,deadline_time deadlineTime,status,urgency_level urgencyLevel,fatigue_level fatigueLevel,completed_at completedAt,completed_fatigue_level completedFatigueLevel,completed_fatigue_weight completedFatigueWeight from schedule where user_id=? and deleted_at is null",
                (rs, i) -> scheduleRow(rs), userId);
    }

    private Map<String, Object> scheduleForUser(long scheduleId, long userId) {
        List<Map<String, Object>> rows = jdbc.query("select id,title,time_type timeType,start_time startTime,end_time endTime,deadline_time deadlineTime,status,urgency_level urgencyLevel,fatigue_level fatigueLevel,completed_at completedAt,completed_fatigue_level completedFatigueLevel,completed_fatigue_weight completedFatigueWeight from schedule where id=? and user_id=? and deleted_at is null",
                (rs, i) -> scheduleRow(rs), scheduleId, userId);
        return rows.isEmpty() ? null : rows.get(0);
    }

    private static Map<String, Object> scheduleRow(ResultSet rs) throws SQLException {
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("id", rs.getLong("id"));
        item.put("title", rs.getString("title"));
        item.put("timeType", rs.getString("timeType"));
        item.put("startTime", rs.getTimestamp("startTime"));
        item.put("endTime", rs.getTimestamp("endTime"));
        item.put("deadlineTime", rs.getTimestamp("deadlineTime"));
        item.put("status", rs.getString("status"));
        item.put("urgencyLevel", rs.getInt("urgencyLevel"));
        item.put("fatigueLevel", rs.getInt("fatigueLevel"));
        item.put("completedAt", rs.getTimestamp("completedAt"));
        item.put("completedFatigueLevel", rs.getObject("completedFatigueLevel"));
        item.put("completedFatigueWeight", rs.getBigDecimal("completedFatigueWeight"));
        return item;
    }

    private Map<String, Object> surveyRow(long userId, LocalDate date) {
        List<Map<String, Object>> rows = jdbc.query("select local_date localDate,timezone_snapshot timezone,score,external_factor_level externalFactorLevel,external_factor_tags externalFactorTags,completed_load_snapshot completedLoadSnapshot,weights_snapshot weightsSnapshot,capacity_before capacityBefore,capacity_after capacityAfter,model_eligible modelEligible,ineligible_reason ineligibleReason,learning_weight learningWeight,completed_level_counts completedLevelCounts,algorithm_version algorithmVersion,submitted_at submittedAt,updated_at updatedAt from fatigue_survey where user_id=? and local_date=?",
                (rs, i) -> {
                    Map<String, Object> row = new LinkedHashMap<>();
                    row.put("localDate", String.valueOf(rs.getDate("localDate")));
                    row.put("timezone", rs.getString("timezone"));
                    row.put("score", rs.getInt("score"));
                    row.put("externalFactorLevel", rs.getInt("externalFactorLevel"));
                    String tags = rs.getString("externalFactorTags");
                    row.put("externalFactorTags", tags == null ? "[]" : tags);
                    row.put("externalFactorTagList", readStringList(tags));
                    row.put("completedLoadSnapshot", rs.getBigDecimal("completedLoadSnapshot"));
                    row.put("weightsSnapshot", rs.getString("weightsSnapshot"));
                    row.put("capacityBefore", rs.getBigDecimal("capacityBefore"));
                    row.put("capacityAfter", rs.getBigDecimal("capacityAfter"));
                    row.put("modelEligible", rs.getBoolean("modelEligible"));
                    row.put("ineligibleReason", rs.getString("ineligibleReason"));
                    row.put("learningWeight", rs.getBigDecimal("learningWeight"));
                    row.put("completedLevelCounts", readLevelCounts(rs.getString("completedLevelCounts")));
                    row.put("algorithmVersion", rs.getInt("algorithmVersion"));
                    row.put("submittedAt", iso(rs.getTimestamp("submittedAt")));
                    row.put("updatedAt", iso(rs.getTimestamp("updatedAt")));
                    return row;
                }, userId, date);
        return rows.isEmpty() ? null : rows.get(0);
    }

    private void upsertSummary(long userId, LocalDate date, Map<String, Object> summary) {
        long revision = number(summary.get("dataRevision"), 0L);
        int updated = jdbc.update("update fatigue_daily_summary set timezone_snapshot=?,planned_load=?,completed_load=?,predicted_score=?,pending_count=?,completed_count=?,algorithm_version=?,data_revision=?,updated_at=utc_timestamp() where user_id=? and local_date=? and data_revision<=?",
                summary.get("timezone"), summary.get("plannedLoad"), summary.get("completedLoad"),
                summary.get("predictedScore"), summary.get("pendingCount"), summary.get("completedCount"),
                ALGORITHM_VERSION, revision, userId, date, revision);
        if (updated > 0) return;
        try {
            jdbc.update("insert into fatigue_daily_summary (user_id,local_date,timezone_snapshot,planned_load,completed_load,predicted_score,pending_count,completed_count,algorithm_version,data_revision) values (?,?,?,?,?,?,?,?,?,?)",
                    userId, date, summary.get("timezone"), summary.get("plannedLoad"), summary.get("completedLoad"),
                    summary.get("predictedScore"), summary.get("pendingCount"), summary.get("completedCount"),
                    ALGORITHM_VERSION, revision);
        } catch (DataIntegrityViolationException ignored) {
            jdbc.update("update fatigue_daily_summary set timezone_snapshot=?,planned_load=?,completed_load=?,predicted_score=?,pending_count=?,completed_count=?,algorithm_version=?,data_revision=?,updated_at=utc_timestamp() where user_id=? and local_date=? and data_revision<=?",
                    summary.get("timezone"), summary.get("plannedLoad"), summary.get("completedLoad"),
                    summary.get("predictedScore"), summary.get("pendingCount"), summary.get("completedCount"),
                    ALGORITHM_VERSION, revision, userId, date, revision);
        }
    }

    private void refreshPersistedSummaries(long userId) {
        Map<String, Object> profile = profileRow(userId);
        if (!trackingEnabled(profile)) return;
        ZoneId zone = userZone(userId);
        LinkedHashSet<LocalDate> dates = new LinkedHashSet<>(jdbc.query(
                "select local_date from fatigue_daily_summary where user_id=?",
                (rs, index) -> rs.getDate(1).toLocalDate(), userId));
        for (Map<String, Object> schedule : schedulesForUser(userId)) {
            LocalDate planned = schedulePlannedDate(schedule, zone);
            LocalDate completed = localDate(schedule.get("completedAt"), zone);
            if (planned != null) dates.add(planned);
            if (completed != null) dates.add(completed);
        }
        long revision = number(profile.get("dataRevision"), 0L);
        for (LocalDate date : dates) dailyResult(userId, date, zone, profile, revision, true);
    }

    private static Map<String, Object> summaryRow(ResultSet rs) throws SQLException {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("localDate", String.valueOf(rs.getDate("localDate")));
        row.put("timezone", rs.getString("timezone"));
        row.put("plannedLoad", rs.getBigDecimal("plannedLoad"));
        row.put("completedLoad", rs.getBigDecimal("completedLoad"));
        row.put("predictedScore", rs.getInt("predictedScore"));
        row.put("pendingCount", rs.getInt("pendingCount"));
        row.put("completedCount", rs.getInt("completedCount"));
        row.put("algorithmVersion", rs.getInt("algorithmVersion"));
        row.put("dataRevision", rs.getLong("dataRevision"));
        return row;
    }

    private static Map<String, Object> emptyHistoryRow(LocalDate date, ZoneId zone) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("localDate", date.toString());
        row.put("timezone", zone.getId());
        row.put("plannedLoad", BigDecimal.ZERO);
        row.put("completedLoad", BigDecimal.ZERO);
        row.put("predictedScore", 0);
        row.put("pendingCount", 0);
        row.put("completedCount", 0);
        row.put("algorithmVersion", ALGORITHM_VERSION);
        row.put("dataRevision", 0L);
        return row;
    }

    private Map<String, Object> surveyComparison(Map<String, Object> daily, Map<String, Object> survey,
                                                 Map<String, Object> profile) {
        int predicted = (int) number(daily.get("predictedScore"), 0);
        int feedback = (int) number(survey.get("score"), 0);
        BigDecimal before = decimal(survey.get("capacityBefore"), decimal(profile.get("capacity75"), DEFAULT_CAPACITY));
        BigDecimal after = decimal(survey.get("capacityAfter"), decimal(profile.get("capacity75"), DEFAULT_CAPACITY));
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("predictedScore", predicted);
        result.put("feedbackScore", feedback);
        result.put("difference", feedback - predicted);
        result.put("modelStage", profile.get("modelStage"));
        result.put("capacityBefore", scale(before));
        result.put("capacityAfter", scale(after));
        result.put("modelEligible", boolValue(survey.get("modelEligible"), false));
        result.put("learningWeight", survey.get("learningWeight"));
        result.put("adjustmentText", adjustmentText(survey, before, after));
        return result;
    }

    private static String adjustmentText(Map<String, Object> survey, BigDecimal before, BigDecimal after) {
        if (!boolValue(survey.get("modelEligible"), false)) return "本次记录会保留，但不会参与自动学习。";
        if (after.compareTo(before) == 0) return "样本仍在积累，本次不会立即改变个人上限。";
        return after.compareTo(before) > 0 ? "本次只会小幅提高你的个人上限。" : "本次只会小幅降低你的个人上限。";
    }

    private Map<String, Object> previewSchedule(Map<String, Object> existing, Map<String, Object> req, String operation) {
        if ("delete".equals(operation) || "cancel".equals(operation)) return null;
        Map<String, Object> result = new LinkedHashMap<>();
        if (existing != null) result.putAll(existing);
        result.put("title", req.containsKey("title") ? Objects.toString(req.get("title"), "") : result.getOrDefault("title", "新日程"));
        result.put("timeType", req.containsKey("timeType") ? Objects.toString(req.get("timeType"), "deadline_task") : result.getOrDefault("timeType", "deadline_task"));
        for (String field : List.of("startTime", "endTime", "deadlineTime")) {
            if (req.containsKey(field)) result.put(field, req.get(field));
        }
        result.put("status", req.containsKey("status") ? Objects.toString(req.get("status"), "pending") : result.getOrDefault("status", "pending"));
        int level = req.containsKey("fatigueLevel") ? strictInt(req, "fatigueLevel") : (int) number(result.get("fatigueLevel"), 3);
        validateLevel(level);
        result.put("fatigueLevel", level);
        int urgencyLevel = req.containsKey("urgencyLevel") ? strictInt(req, "urgencyLevel") : (int) number(result.get("urgencyLevel"), 3);
        if (urgencyLevel < 1 || urgencyLevel > 5) throw new BusinessException(400, "urgencyLevel is invalid");
        result.put("urgencyLevel", urgencyLevel);
        return result;
    }

    private List<Map<String, Object>> previewContributors(Map<String, Object> beforeDaily,
                                                          Map<String, Object> existing,
                                                          Map<String, Object> proposed,
                                                          LocalDate date,
                                                          LocalDate oldDate,
                                                          LocalDate proposedDate,
                                                          Map<String, Object> profile) {
        List<Map<String, Object>> result = new ArrayList<>();
        Object raw = beforeDaily.get("topContributors");
        if (raw instanceof List<?> list) {
            for (Object item : list) {
                if (item instanceof Map<?, ?> map) {
                    Map<String, Object> copy = new LinkedHashMap<>();
                    map.forEach((key, value) -> copy.put(String.valueOf(key), value));
                    result.add(copy);
                }
            }
        }
        if (existing != null && date.equals(oldDate)) {
            long existingId = number(existing.get("id"), -1L);
            result.removeIf(item -> number(item.get("scheduleId"), -2L) == existingId);
        }
        if (proposed != null && date.equals(proposedDate)) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("scheduleId", number(proposed.get("id"), 0L));
            item.put("title", proposed.get("title"));
            item.put("urgencyLevel", number(proposed.get("urgencyLevel"), 3));
            item.put("fatigueLevel", number(proposed.get("fatigueLevel"), 3));
            item.put("weight", scale(weightFor(profile, (int) number(proposed.get("fatigueLevel"), 3))));
            result.add(item);
        }
        result.sort(Comparator.comparing((Map<String, Object> item) -> decimal(item.get("weight"))).reversed()
                .thenComparing(item -> Objects.toString(item.get("title"), "")));
        return result.stream().limit(5).toList();
    }

    private Map<String, Object> fatigueProjection(Map<String, Object> daily) {
        return projection(decimal(daily.get("plannedLoad")), decimal(daily.get("capacity75"), DEFAULT_CAPACITY));
    }

    private Map<String, Object> projection(BigDecimal plannedLoad, BigDecimal capacity) {
        int score = predictedScore(plannedLoad, capacity);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("plannedLoad", scale(plannedLoad));
        result.put("capacity75", scale(capacity));
        result.put("predictedScore", score);
        result.put("level", thresholdBand(score));
        return result;
    }

    private static String previewWarning(LocalDate date, int score) {
        return "保存后，" + date + " 的个人日程预计负荷将达到 " + score + " 分。建议调整低紧急程度日程或预留休息时间。";
    }

    private static LocalDate schedulePlannedDate(Map<String, Object> schedule, ZoneId zone) {
        String type = String.valueOf(schedule.getOrDefault("timeType", ""));
        Instant instant = switch (type) {
            case "deadline_task" -> instant(schedule.get("deadlineTime"));
            case "duration_task", "point_event" -> instant(schedule.get("startTime"));
            default -> null;
        };
        return instant == null ? null : instant.atZone(zone).toLocalDate();
    }

    private static LocalDate localDate(Object value, ZoneId zone) {
        Instant instant = instant(value);
        return instant == null ? null : instant.atZone(zone).toLocalDate();
    }

    private static Instant instant(Object value) {
        if (value == null) return null;
        if (value instanceof Timestamp timestamp) return timestamp.toInstant();
        if (value instanceof Instant instant) return instant;
        try {
            return OffsetDateTime.parse(String.valueOf(value)).toInstant();
        } catch (DateTimeException ignored) {
            try {
                return Instant.parse(String.valueOf(value));
            } catch (DateTimeException ignoredAgain) {
                return null;
            }
        }
    }

    private ZoneId userZone(long userId) {
        String timezone = jdbc.queryForObject("select timezone from `user` where id=? and deleted_at is null", String.class, userId);
        return safeZone(timezone);
    }

    private static ZoneId safeZone(String timezone) {
        try {
            return ZoneId.of(timezone == null || timezone.isBlank() ? "Asia/Shanghai" : timezone);
        } catch (DateTimeException ignored) {
            return ZoneId.of("Asia/Shanghai");
        }
    }

    private boolean isQuietTime(long userId, LocalTime time) {
        List<Map<String, Object>> rows = jdbc.query("select quiet_start_time quietStart,quiet_end_time quietEnd from notification_preference where user_id=?",
                (rs, i) -> {
                    Map<String, Object> row = new HashMap<>();
                    row.put("start", rs.getTime("quietStart"));
                    row.put("end", rs.getTime("quietEnd"));
                    return row;
                }, userId);
        if (rows.isEmpty() || rows.get(0).get("start") == null || rows.get(0).get("end") == null) return false;
        LocalTime start = ((Time) rows.get(0).get("start")).toLocalTime();
        LocalTime end = ((Time) rows.get(0).get("end")).toLocalTime();
        return start.equals(end)
                || (start.isBefore(end) ? !time.isBefore(start) && time.isBefore(end) : !time.isBefore(start) || time.isBefore(end));
    }

    private boolean trackingEnabled(Map<String, Object> profile) {
        return featureEnabledFor(number(profile.get("userId"), 0L))
                && boolValue(profile.get("fatigueTrackingEnabled"), true);
    }

    private boolean featureEnabledFor(long userId) {
        if (!fatigueFeatureEnabled) return false;
        String configured = Objects.toString(fatigueAllowedUserIds, "").trim();
        if (configured.isBlank()) return true;
        for (String token : configured.split(",")) {
            try {
                if (Long.parseLong(token.trim()) == userId) return true;
            } catch (NumberFormatException ignored) {
                // Ignore malformed rollout entries instead of enabling an unintended account.
            }
        }
        return false;
    }

    private void incrementDataRevision(long userId) {
        jdbc.update("update user_fatigue_profile set data_revision=data_revision+1,updated_at=utc_timestamp() where user_id=?", userId);
    }

    private static Map<Integer, BigDecimal> weights(Map<String, Object> profile) {
        Map<Integer, BigDecimal> result = new LinkedHashMap<>();
        for (int level = 1; level <= 5; level++) {
            result.put(level, decimal(profile.get("level" + level + "Weight"), BigDecimal.valueOf(defaultWeight(level))));
        }
        return result;
    }

    private static BigDecimal weightFor(Map<String, Object> profile, int level) {
        return weights(profile).getOrDefault(level, BigDecimal.valueOf(defaultWeight(level)));
    }

    private static boolean sameWeights(Map<Integer, BigDecimal> left, Map<Integer, BigDecimal> right) {
        for (int level = 1; level <= 5; level++) {
            if (left.getOrDefault(level, BigDecimal.ZERO).compareTo(right.getOrDefault(level, BigDecimal.ZERO)) != 0) return false;
        }
        return true;
    }

    private static int defaultWeight(int level) {
        return switch (level) {
            case 1 -> 1;
            case 2 -> 2;
            case 3 -> 3;
            case 4 -> 5;
            case 5 -> 8;
            default -> 3;
        };
    }

    private static int predictedScore(BigDecimal load, BigDecimal capacity) {
        if (capacity.signum() <= 0) return 100;
        return load.multiply(BigDecimal.valueOf(75)).divide(capacity, 0, RoundingMode.HALF_UP)
                .max(BigDecimal.ZERO).min(BigDecimal.valueOf(100)).intValue();
    }

    private static String thresholdBand(int score) {
        return score < 50 ? "comfortable" : score < 70 ? "full" : score < 85 ? "tired" : score < 100 ? "high" : "overloaded";
    }

    private static String alertBand(int score) {
        return score >= 100 ? "overloaded" : score >= 85 ? "high" : score >= 70 ? "tired" : null;
    }

    private static int alertSeverity(String band) {
        return switch (band == null ? "" : band) {
            case "tired" -> 1;
            case "high" -> 2;
            case "overloaded" -> 3;
            default -> 0;
        };
    }

    private static String highestBand(String first, String second) {
        return alertSeverity(first) >= alertSeverity(second) ? first : second;
    }

    private static String contributorText(Object raw) {
        if (!(raw instanceof List<?> list)) return "";
        return list.stream()
                .filter(Map.class::isInstance)
                .map(Map.class::cast)
                .map(item -> Objects.toString(item.get("title"), "").trim())
                .filter(title -> !title.isBlank())
                .distinct()
                .limit(2)
                .map(title -> title.length() > 30 ? title.substring(0, 30) + "..." : title)
                .reduce((left, right) -> left + "、" + right)
                .orElse("");
    }

    private static String modelStageLabel(String stage) {
        return switch (stage) {
            case "personalized" -> "已个性化";
            case "calibrating" -> "校准中";
            default -> "基础估算";
        };
    }

    private static String confidence(Map<String, Object> profile) {
        int days = (int) number(profile.get("validSurveyDays"), 0);
        String stage = Objects.toString(profile.get("modelStage"), "default");
        if ("personalized".equals(stage)) return "high";
        return days >= 7 ? "medium" : "low";
    }

    private static Map<String, Integer> levelCountMap(int[] counts) {
        Map<String, Integer> result = new LinkedHashMap<>();
        for (int index = 0; index < 5; index++) result.put(String.valueOf(index + 1), counts[index]);
        return result;
    }

    private static Map<String, Integer> levelCounts(Object raw) {
        Map<String, Integer> result = new LinkedHashMap<>();
        if (raw instanceof Map<?, ?> map) {
            for (int level = 1; level <= 5; level++) {
                Object value = map.get(String.valueOf(level));
                if (value == null) value = map.get(level);
                result.put(String.valueOf(level), (int) number(value, 0));
            }
        } else {
            for (int level = 1; level <= 5; level++) result.put(String.valueOf(level), 0);
        }
        return result;
    }

    private int[] levelCountArray(String raw) {
        Map<String, Integer> counts = readLevelCounts(raw);
        int[] result = new int[5];
        for (int level = 1; level <= 5; level++) result[level - 1] = counts.getOrDefault(String.valueOf(level), 0);
        return result;
    }

    private Map<String, Integer> readLevelCounts(String raw) {
        if (raw == null || raw.isBlank()) return levelCountMap(new int[5]);
        try {
            Map<String, Object> values = objectMapper.readValue(raw, new TypeReference<>() { });
            return levelCounts(values);
        } catch (Exception ignored) {
            return levelCountMap(new int[5]);
        }
    }

    private List<String> readStringList(String raw) {
        if (raw == null || raw.isBlank()) return List.of();
        try {
            return objectMapper.readValue(raw, new TypeReference<>() { });
        } catch (Exception ignored) {
            return List.of();
        }
    }

    private List<Map<String, Object>> recurringExternalFactors(long userId, LocalDate throughDate) {
        List<String> tagRows = jdbc.query(
                "select external_factor_tags from fatigue_survey where user_id=? and local_date between ? and ? and external_factor_level>0",
                (rs, index) -> rs.getString("external_factor_tags"),
                userId, throughDate.minusDays(13), throughDate);
        Map<String, Integer> counts = new HashMap<>();
        for (String raw : tagRows) {
            for (String tag : new LinkedHashSet<>(readStringList(raw))) {
                counts.merge(tag, 1, Integer::sum);
            }
        }
        return counts.entrySet().stream()
                .filter(entry -> entry.getValue() >= 4)
                .sorted(Map.Entry.<String, Integer>comparingByValue().reversed().thenComparing(Map.Entry.comparingByKey()))
                .map(entry -> {
                    Map<String, Object> item = new LinkedHashMap<>();
                    item.put("tag", entry.getKey());
                    item.put("days", entry.getValue());
                    return item;
                })
                .toList();
    }

    private String json(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception ex) {
            throw new BusinessException(400, "invalid structured data");
        }
    }

    private static List<String> stringList(Object raw) {
        if (raw == null) return List.of();
        if (!(raw instanceof List<?> list)) throw new BusinessException(400, "externalFactorTags must be a list");
        return list.stream().map(value -> Objects.toString(value, "").trim()).filter(value -> !value.isBlank()).distinct().toList();
    }

    private static List<LocalDate> distinctDates(List<LocalDate> dates) {
        if (dates == null) return List.of();
        return dates.stream().filter(Objects::nonNull).distinct().sorted().toList();
    }

    private static void validateHistoryRange(LocalDate from, LocalDate to) {
        if (to.isBefore(from)) throw new BusinessException(400, "dateTo must not be before dateFrom");
        if (from.plusDays(MAX_HISTORY_RANGE_DAYS).isBefore(to)) throw new BusinessException(400, "date range is too large");
    }

    private static void validateLevel(int level) {
        if (level < 1 || level > 5) throw new BusinessException(400, "fatigueLevel is invalid");
    }

    private static String csvValue(Object raw) {
        if (raw == null) return "";
        String value = String.valueOf(raw);
        if (value.contains(",") || value.contains("\"") || value.contains("\n") || value.contains("\r")) {
            return "\"" + value.replace("\"", "\"\"") + "\"";
        }
        return value;
    }

    private static BigDecimal scale(BigDecimal value) {
        return value.setScale(3, RoundingMode.HALF_UP).stripTrailingZeros();
    }

    private static BigDecimal decimal(Object value) {
        return decimal(value, BigDecimal.ZERO);
    }

    private static BigDecimal decimal(Object value, BigDecimal fallback) {
        if (value == null) return fallback;
        if (value instanceof BigDecimal decimal) return decimal;
        try {
            return new BigDecimal(String.valueOf(value));
        } catch (NumberFormatException ignored) {
            return fallback;
        }
    }

    private static long number(Object value, long fallback) {
        if (value instanceof Number number) return number.longValue();
        try {
            return Long.parseLong(String.valueOf(value));
        } catch (Exception ignored) {
            return fallback;
        }
    }

    private static int strictInt(Map<String, Object> req, String field) {
        Object value = req == null ? null : req.get(field);
        if (!(value instanceof Byte || value instanceof Short || value instanceof Integer || value instanceof Long)) {
            throw new BusinessException(400, field + " is invalid");
        }
        long parsed = ((Number) value).longValue();
        if (parsed < Integer.MIN_VALUE || parsed > Integer.MAX_VALUE) {
            throw new BusinessException(400, field + " is invalid");
        }
        return (int) parsed;
    }

    private static long requiredLong(Map<String, Object> req, String field) {
        Object value = req.get(field);
        if (value == null) throw new BusinessException(400, field + " is required");
        long parsed = number(value, -1L);
        if (parsed <= 0) throw new BusinessException(400, field + " is invalid");
        return parsed;
    }

    private static boolean bool(Map<String, Object> req, String key, Map<String, Object> current, boolean fallback) {
        if (!req.containsKey(key)) return boolValue(current.get(key), fallback);
        Object value = req.get(key);
        if (!(value instanceof Boolean)) throw new BusinessException(400, key + " is invalid");
        return (Boolean) value;
    }

    private static boolean boolValue(Object value, boolean fallback) {
        return value instanceof Boolean bool ? bool : fallback;
    }

    private static LocalTime parseSurveyTime(Object raw) {
        if (raw == null || String.valueOf(raw).isBlank()) return LocalTime.of(DEFAULT_SURVEY_HOUR, DEFAULT_SURVEY_MINUTE);
        String value = String.valueOf(raw);
        try {
            return LocalTime.parse(value.length() == 5 ? value + ":00" : value);
        } catch (DateTimeException ex) {
            throw new BusinessException(400, "surveyTime is invalid");
        }
    }

    private static LocalDate parseDate(String raw, String field) {
        try {
            return LocalDate.parse(raw);
        } catch (DateTimeException ex) {
            throw new BusinessException(400, field + " is invalid");
        }
    }

    private static Timestamp timestamp(Object value) {
        return value instanceof Timestamp timestamp ? timestamp : null;
    }

    private static String dateString(java.sql.Date date) {
        return date == null ? "" : date.toLocalDate().toString();
    }

    private static String iso(Timestamp timestamp) {
        return timestamp == null ? "" : OffsetDateTime.ofInstant(timestamp.toInstant(), ZoneOffset.UTC).toString();
    }
}
