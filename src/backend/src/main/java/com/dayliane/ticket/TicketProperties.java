package com.dayliane.ticket;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/** 工单模块配置：数值集中在此，首版不建设专门的管理界面。 */
@Component
public class TicketProperties {

    @Value("${dayliane.ticket.storage-dir:./data/tickets}")
    private String storageDir;

    @Value("${dayliane.ticket.max-image-bytes:10485760}")
    private long maxImageBytes;

    @Value("${dayliane.ticket.max-image-pixels:40000000}")
    private long maxImagePixels;

    @Value("${dayliane.ticket.temp-attachment-retention-hours:24}")
    private long tempAttachmentRetentionHours;

    @Value("${dayliane.ticket.rate-limit.create-per-minute:2}")
    private int createPerMinute;

    @Value("${dayliane.ticket.rate-limit.create-per-day:20}")
    private int createPerDay;

    @Value("${dayliane.ticket.rate-limit.reply-per-minute:10}")
    private int replyPerMinute;

    @Value("${dayliane.ticket.rate-limit.reply-per-day:100}")
    private int replyPerDay;

    @Value("${dayliane.ticket.rate-limit.upload-per-day-mb:200}")
    private long uploadPerDayMb;

    @Value("${dayliane.ticket.rate-limit.read-per-minute:120}")
    private int readPerMinute;

    public String getStorageDir() { return storageDir; }
    public long getMaxImageBytes() { return maxImageBytes; }
    public long getMaxImagePixels() { return maxImagePixels; }
    public long getTempAttachmentRetentionHours() { return tempAttachmentRetentionHours; }
    public int getCreatePerMinute() { return createPerMinute; }
    public int getCreatePerDay() { return createPerDay; }
    public int getReplyPerMinute() { return replyPerMinute; }
    public int getReplyPerDay() { return replyPerDay; }
    public long getUploadPerDayMb() { return uploadPerDayMb; }
    public int getReadPerMinute() { return readPerMinute; }
}
