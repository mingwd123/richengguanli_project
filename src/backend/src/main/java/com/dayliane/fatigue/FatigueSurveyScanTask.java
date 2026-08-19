package com.dayliane.fatigue;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
public class FatigueSurveyScanTask {
    private static final Logger log = LoggerFactory.getLogger(FatigueSurveyScanTask.class);
    private final FatigueService fatigueService;

    public FatigueSurveyScanTask(FatigueService fatigueService) {
        this.fatigueService = fatigueService;
    }

    @Scheduled(fixedRate = 60_000)
    public void scan() {
        try {
            int created = fatigueService.scanSurveyPrompts();
            if (created > 0) log.info("Fatigue survey scan created {} notifications", created);
        } catch (Exception ex) {
            log.error("Fatigue survey scan failed", ex);
        }
    }

    @Scheduled(fixedRate = 300_000, initialDelay = 30_000)
    public void scanAlerts() {
        try {
            int created = fatigueService.scanFatigueAlerts();
            if (created > 0) log.info("Fatigue alert scan created {} notifications", created);
        } catch (Exception ex) {
            log.error("Fatigue alert scan failed", ex);
        }
    }

    @Scheduled(cron = "0 20 3 * * *", zone = "UTC")
    public void cleanupRetention() {
        try {
            int deleted = fatigueService.cleanupRetention();
            if (deleted > 0) log.info("Fatigue retention cleanup deleted {} rows", deleted);
        } catch (Exception ex) {
            log.error("Fatigue retention cleanup failed", ex);
        }
    }
}
