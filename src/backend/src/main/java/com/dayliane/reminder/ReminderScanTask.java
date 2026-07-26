package com.dayliane.reminder;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
public class ReminderScanTask {

    private static final Logger log = LoggerFactory.getLogger(ReminderScanTask.class);

    private final ReminderService reminderService;

    public ReminderScanTask(ReminderService reminderService) {
        this.reminderService = reminderService;
    }

    @Scheduled(fixedRate = 60_000)
    public void scan() {
        try {
            int sent = reminderService.scanReminders();
            if (sent > 0) {
                log.info("Reminder scan sent {} notifications", sent);
            }
        } catch (Exception e) {
            log.error("Reminder scan failed", e);
        }
    }
}
