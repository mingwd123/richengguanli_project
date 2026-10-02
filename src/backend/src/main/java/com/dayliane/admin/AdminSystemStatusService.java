package com.dayliane.admin;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.zaxxer.hikari.HikariDataSource;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.core.env.Environment;

import javax.sql.DataSource;
import java.io.File;
import java.lang.management.ManagementFactory;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

@Service
public class AdminSystemStatusService {
    private final JdbcTemplate jdbc;
    private final DataSource dataSource;
    private final MeterRegistry metrics;
    private final ObjectMapper json;
    private final String statusFile;
    private final long maxBackupHours;
    private final SecurityTelemetryService telemetry;
    private final RecentOperationalMetrics recent;
    private final String environment;
    private final Instant startedAt = Instant.ofEpochMilli(ManagementFactory.getRuntimeMXBean().getStartTime());

    public AdminSystemStatusService(JdbcTemplate jdbc, DataSource dataSource, MeterRegistry metrics, ObjectMapper json,
                                    @Value("${app.ops.status-file:}") String statusFile,
                                    @Value("${app.ops.backup-max-age-hours:26}") long maxBackupHours,
                                    @Value("${app.ops.environment:}") String deploymentEnvironment,
                                    Environment profiles, SecurityTelemetryService telemetry, RecentOperationalMetrics recent) {
        this.jdbc = jdbc;
        this.dataSource = dataSource;
        this.metrics = metrics;
        this.json = json;
        this.statusFile = statusFile;
        this.maxBackupHours = Math.max(1, maxBackupHours);
        this.telemetry = telemetry;
        this.recent = recent;
        this.environment = deploymentEnvironment.isBlank()
                ? (profiles.getActiveProfiles().length == 0 ? "未标注环境" : String.join(", ", profiles.getActiveProfiles()))
                : deploymentEnvironment;
    }

    public Map<String, Object> status() {
        List<String> alerts = new ArrayList<>();
        Map<String, Object> result = new LinkedHashMap<>();
        long start = System.nanoTime();
        boolean connected;
        try { connected = Integer.valueOf(1).equals(jdbc.queryForObject("select 1", Integer.class)); }
        catch (RuntimeException ex) { connected = false; }
        if (!connected) alerts.add("数据库健康检查失败");
        Map<String, Object> database = new LinkedHashMap<>();
        database.put("status", connected ? "up" : "down");
        database.put("latencyMs", Math.round((System.nanoTime() - start) / 1000000.0));
        if (dataSource instanceof HikariDataSource hikari && hikari.getHikariPoolMXBean() != null) {
            database.put("activeConnections", hikari.getHikariPoolMXBean().getActiveConnections());
            database.put("idleConnections", hikari.getHikariPoolMXBean().getIdleConnections());
            database.put("maxConnections", hikari.getMaximumPoolSize());
        }
        result.put("database", database);
        long requests = 0;
        long errors = 0;
        double totalMs = 0;
        for (Timer timer : metrics.find("http.server.requests").timers()) {
            requests += timer.count();
            totalMs += timer.totalTime(TimeUnit.MILLISECONDS);
            String code = timer.getId().getTag("status");
            if (code != null && code.startsWith("5")) errors += timer.count();
        }
        Map<String, Object> api = new LinkedHashMap<>();
        api.put("requests", requests);
        api.put("errors", errors);
        api.put("averageLatencyMs", requests == 0 ? null : Math.round(totalMs / requests));
        api.put("errorRate", requests == 0 ? null : Math.round(errors * 1000.0 / requests) / 10.0);
        api.put("since", startedAt.toString());
        result.put("api", api);
        Map<String, Object> window = recent.snapshot();
        result.put("recentApi", window);
        result.put("environment", environment);
        result.put("telemetry", telemetry.health());
        if (((Number) window.get("errors")).longValue() > 0) alerts.add("最近 15 分钟存在 HTTP 5xx");
        if (window.get("errorLogs") instanceof Number count && count.longValue() > 0) alerts.add("最近 15 分钟存在 ERROR 日志");
        Map<String, Object> telemetryHealth = telemetry.health();
        if (((Number) telemetryHealth.get("failed")).longValue() + ((Number) telemetryHealth.get("dropped")).longValue() > 0)
            alerts.add("本进程曾出现采集写入失败或丢弃，请检查采集计数");
        Runtime runtime = Runtime.getRuntime();
        result.put("heap", resource(runtime.totalMemory() - runtime.freeMemory(), runtime.maxMemory()));
        File disk = new File(".");
        result.put("disk", resource(disk.getTotalSpace() - disk.getUsableSpace(), disk.getTotalSpace()));
        if (disk.getTotalSpace() > 0 && disk.getUsableSpace() * 1.0 / disk.getTotalSpace() < .1) alerts.add("磁盘可用空间低于 10%");
        if ((runtime.totalMemory() - runtime.freeMemory()) * 1.0 / runtime.maxMemory() > .85) alerts.add("JVM 堆内存使用超过 85%");
        if (requests >= 20 && errors * 1.0 / requests > .05) alerts.add("本进程 HTTP 5xx 比例超过 5%");
        result.put("backup", Map.of("status", "unknown"));
        result.put("containers", List.of());
        result.put("monitorStatus", "unconfigured");
        if (!statusFile.isBlank()) readStatusFile(result, alerts);
        if ("unconfigured".equals(result.get("monitorStatus"))) alerts.add("尚未接入备份与容器监控");
        result.put("alerts", alerts);
        result.put("status", !connected ? "down" : alerts.isEmpty() ? "up" : "warning");
        result.put("checkedAt", Instant.now().toString());
        result.put("readOnly", true);
        return result;
    }

    private void readStatusFile(Map<String, Object> result, List<String> alerts) {
        try {
            Path file = Path.of(statusFile);
            if (Files.size(file) > 65536) throw new IllegalArgumentException();
            JsonNode root = json.readTree(file.toFile());
            Instant observedAt = Instant.parse(root.path("observedAt").asText());
            long age = Duration.between(observedAt, Instant.now()).toSeconds();
            boolean stale = age < -60 || age > 600;
            result.put("monitorStatus", stale ? "stale" : "current");
            result.put("monitorObservedAt", observedAt.toString());
            if (stale) alerts.add("运维采集文件超过 10 分钟未更新或时间异常");
            JsonNode backupNode = root.path("backup");
            Map<String, Object> backup = new LinkedHashMap<>();
            if (backupNode.path("lastSuccessAt").isTextual()) {
                Instant last = Instant.parse(backupNode.path("lastSuccessAt").asText());
                long hours = Duration.between(last, Instant.now()).toHours();
                boolean overdue = last.isAfter(Instant.now()) || hours >= maxBackupHours;
                backup.put("lastSuccessAt", last.toString());
                backup.put("status", stale ? "unknown" : overdue ? "overdue" : "up");
                backup.put("offsite", backupNode.path("offsite").isBoolean() ? backupNode.path("offsite").booleanValue() : null);
                backup.put("maxAgeHours", maxBackupHours);
                if (overdue) alerts.add("备份已过期或备份时间异常");
                if (!backupNode.path("offsite").asBoolean(false)) alerts.add("异地备份尚未确认");
            } else {
                backup.put("status", "unknown");
                alerts.add("没有可验证的成功备份记录");
            }
            result.put("backup", backup);
            List<Map<String, String>> containers = new ArrayList<>();
            for (JsonNode node : root.path("containers")) {
                if (containers.size() >= 50) break;
                String name = node.path("name").asText("container");
                String state = node.path("status").asText("unknown");
                if (!List.of("up", "down", "unknown").contains(state) || stale) state = "unknown";
                containers.add(Map.of("name", name.substring(0, Math.min(name.length(), 100)), "status", state));
                if (!"up".equals(state)) alerts.add("容器健康状态异常：" + containers.get(containers.size() - 1).get("name"));
            }
            result.put("containers", containers);
            if (containers.isEmpty()) alerts.add("未配置容器监控目标");
        } catch (Exception ex) {
            result.put("monitorStatus", "unavailable");
            alerts.add("运维状态文件不可读或格式不正确");
        }
    }

    private static Map<String, Object> resource(long used, long total) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("usedBytes", used);
        result.put("totalBytes", total);
        result.put("usedPercent", total <= 0 ? null : Math.round(used * 1000.0 / total) / 10.0);
        return result;
    }
}
