package com.dayliane.ticket;

import com.dayliane.auth.AuthService;
import com.dayliane.common.ApiResponse;
import com.dayliane.common.BusinessException;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.util.Map;

/** 用户端工单接口（计划 §10.1）。所有接口要求已登录有效用户，模块关闭时统一 503 + TICKET_FEATURE_DISABLED。 */
@RestController
@RequestMapping("/api/v1/tickets")
public class TicketController {

    private final TicketService ticketService;
    private final TicketSettingService settingService;
    private final TicketAttachmentStorage storage;
    private final TicketRateLimiter rateLimiter;
    private final TicketProperties properties;
    private final AuthService authService;

    public TicketController(TicketService ticketService, TicketSettingService settingService,
                            TicketAttachmentStorage storage, TicketRateLimiter rateLimiter,
                            TicketProperties properties, AuthService authService) {
        this.ticketService = ticketService;
        this.settingService = settingService;
        this.storage = storage;
        this.rateLimiter = rateLimiter;
        this.properties = properties;
        this.authService = authService;
    }

    /** 查询模块开关；关闭时仍可调用，前端据此隐藏入口。 */
    @GetMapping("/settings")
    public ApiResponse<Map<String, Object>> settings(HttpServletRequest request) {
        authService.requireUser(request.getHeader("Authorization"));
        return ApiResponse.success(settingService.current());
    }

    @GetMapping
    public ApiResponse<Map<String, Object>> list(HttpServletRequest request, @RequestParam Map<String, String> params) {
        long userId = authService.requireUser(request.getHeader("Authorization"));
        return ApiResponse.success(ticketService.list(userId, new java.util.LinkedHashMap<>(params)));
    }

    @GetMapping("/{id}")
    public ApiResponse<Map<String, Object>> detail(HttpServletRequest request, @PathVariable long id) {
        long userId = authService.requireUser(request.getHeader("Authorization"));
        return ApiResponse.success(ticketService.detail(id, userId));
    }

    @GetMapping("/{id}/messages")
    public ApiResponse<Map<String, Object>> messages(HttpServletRequest request, @PathVariable long id,
                                                     @RequestParam(defaultValue = "1") int page,
                                                     @RequestParam(defaultValue = "20") int size) {
        long userId = authService.requireUser(request.getHeader("Authorization"));
        return ApiResponse.success(ticketService.messages(id, userId, page, size));
    }

    @GetMapping("/{id}/events")
    public ApiResponse<Map<String, Object>> events(HttpServletRequest request, @PathVariable long id) {
        long userId = authService.requireUser(request.getHeader("Authorization"));
        return ApiResponse.success(ticketService.events(id, userId));
    }

    @PostMapping
    public ApiResponse<Map<String, Object>> create(HttpServletRequest request, @RequestBody Map<String, Object> body) {
        long userId = authService.requireUser(request.getHeader("Authorization"));
        return ApiResponse.success(ticketService.create(userId, body));
    }

    @PostMapping("/{id}/messages")
    public ApiResponse<Map<String, Object>> reply(HttpServletRequest request, @PathVariable long id,
                                                  @RequestBody Map<String, Object> body) {
        long userId = authService.requireUser(request.getHeader("Authorization"));
        return ApiResponse.success(ticketService.reply(userId, id, body));
    }

    @PutMapping("/{id}/follow")
    public ApiResponse<Map<String, Object>> follow(HttpServletRequest request, @PathVariable long id) {
        long userId = authService.requireUser(request.getHeader("Authorization"));
        ticketService.follow(userId, id);
        return ApiResponse.success(ticketService.viewerState(id, userId));
    }

    @DeleteMapping("/{id}/follow")
    public ApiResponse<Map<String, Object>> unfollow(HttpServletRequest request, @PathVariable long id) {
        long userId = authService.requireUser(request.getHeader("Authorization"));
        ticketService.unfollow(userId, id);
        return ApiResponse.success(ticketService.viewerState(id, userId));
    }

    @PutMapping("/{id}/same-issue")
    public ApiResponse<Map<String, Object>> sameIssue(HttpServletRequest request, @PathVariable long id) {
        long userId = authService.requireUser(request.getHeader("Authorization"));
        return ApiResponse.success(ticketService.markSameIssue(userId, id));
    }

    @DeleteMapping("/{id}/same-issue")
    public ApiResponse<Map<String, Object>> removeSameIssue(HttpServletRequest request, @PathVariable long id) {
        long userId = authService.requireUser(request.getHeader("Authorization"));
        return ApiResponse.success(ticketService.unmarkSameIssue(userId, id));
    }

    @PostMapping("/{id}/confirm")
    public ApiResponse<Map<String, Object>> confirm(HttpServletRequest request, @PathVariable long id) {
        long userId = authService.requireUser(request.getHeader("Authorization"));
        return ApiResponse.success(ticketService.confirmResolved(userId, id));
    }

    @PostMapping("/{id}/reopen")
    public ApiResponse<Map<String, Object>> reopen(HttpServletRequest request, @PathVariable long id,
                                                   @RequestBody Map<String, Object> body) {
        long userId = authService.requireUser(request.getHeader("Authorization"));
        return ApiResponse.success(ticketService.reopenByAuthor(userId, id, body));
    }

    @PostMapping("/{id}/withdraw")
    public ApiResponse<Map<String, Object>> withdraw(HttpServletRequest request, @PathVariable long id,
                                                     @RequestBody(required = false) Map<String, Object> body) {
        long userId = authService.requireUser(request.getHeader("Authorization"));
        return ApiResponse.success(ticketService.withdraw(userId, id, body));
    }

    @PostMapping("/attachments")
    public ApiResponse<Map<String, Object>> upload(HttpServletRequest request,
                                                   @RequestParam("file") MultipartFile file) {
        long userId = authService.requireUser(request.getHeader("Authorization"));
        settingService.requireEnabled();
        rateLimiter.checkAndRecord(userId, "upload", properties.getReplyPerMinute(), Integer.MAX_VALUE);
        long remaining = rateLimiter.checkUploadBytes(userId, properties.getUploadPerDayMb() * 1024 * 1024);
        if (file != null && !file.isEmpty() && file.getSize() > remaining) {
            throw new BusinessException(429, "daily upload quota exceeded, please retry tomorrow");
        }
        TicketAttachmentStorage.StoredAttachment stored = storage.store("user", userId, file);
        rateLimiter.recordUploadBytes(userId, stored.sizeBytes());
        return ApiResponse.success(Map.of(
                "id", stored.id(),
                "mimeType", stored.mimeType(),
                "sizeBytes", stored.sizeBytes(),
                "width", stored.width(),
                "height", stored.height(),
                "name", stored.originalName()));
    }

    /** 读取图片：上传者本人、绑定后的所有有效用户（需开关开启且工单未隐藏）。 */
    @GetMapping("/attachments/{id}")
    public ResponseEntity<byte[]> read(HttpServletRequest request, @PathVariable long id) {
        long userId = authService.requireUser(request.getHeader("Authorization"));
        rateLimiter.checkAndRecord(userId, "read", properties.getReadPerMinute(), Integer.MAX_VALUE);
        Map<String, Object> attachment = storage.loadAttachment(id);
        if (attachment == null) throw new BusinessException(404, "attachment not found");
        String uploaderType = String.valueOf(attachment.get("uploader_type"));
        long uploaderId = ((Number) attachment.get("uploader_id")).longValue();
        boolean owner = "user".equals(uploaderType) && uploaderId == userId;
        if (!owner) {
            settingService.requireEnabled();
            if (!"bound".equals(String.valueOf(attachment.get("state")))) {
                throw new BusinessException(403, "attachment is not available");
            }
            Long ticketId = attachment.get("ticket_id") == null ? null : ((Number) attachment.get("ticket_id")).longValue();
            if (ticketId != null && ticketService.isHidden(ticketId)) {
                throw new BusinessException(404, "attachment not found");
            }
        }
        byte[] bytes = storage.readFile(String.valueOf(attachment.get("storage_key")));
        return ResponseEntity.ok()
                .header(HttpHeaders.CACHE_CONTROL, "private, max-age=3600")
                .contentType(MediaType.parseMediaType(String.valueOf(attachment.get("mime_type"))))
                .body(bytes);
    }

    @DeleteMapping("/attachments/{id}")
    public ApiResponse<Map<String, Object>> delete(HttpServletRequest request, @PathVariable long id) {
        long userId = authService.requireUser(request.getHeader("Authorization"));
        storage.deleteTemporary("user", userId, id);
        Map<String, Object> attachment = storage.loadAttachment(id);
        if (attachment != null) storage.deleteFileQuietly(String.valueOf(attachment.get("storage_key")));
        return ApiResponse.success(Map.of("ok", true));
    }
}
