package com.dayliane.ticket;

import com.dayliane.admin.AdminService;
import com.dayliane.auth.AuthService;
import com.dayliane.common.ApiResponse;
import com.dayliane.common.BusinessException;
import jakarta.servlet.http.HttpServletRequest;
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

/**
 * 管理端工单接口（计划 §10.2）。
 *
 * 管理端始终可查看与处理（不受用户端功能开关限制）；全局开关仅超级管理员可切换。
 * 所有管理动作写入 admin_operation_log。
 */
@RestController
@RequestMapping("/api/v1/admin/tickets")
public class AdminTicketController {

    private final TicketService ticketService;
    private final TicketSettingService settingService;
    private final TicketAttachmentStorage storage;
    private final TicketRateLimiter rateLimiter;
    private final TicketProperties properties;
    private final AuthService authService;
    private final AdminService adminService;

    public AdminTicketController(TicketService ticketService, TicketSettingService settingService,
                                 TicketAttachmentStorage storage, TicketRateLimiter rateLimiter,
                                 TicketProperties properties, AuthService authService, AdminService adminService) {
        this.ticketService = ticketService;
        this.settingService = settingService;
        this.storage = storage;
        this.rateLimiter = rateLimiter;
        this.properties = properties;
        this.authService = authService;
        this.adminService = adminService;
    }

    @GetMapping("/settings")
    public ApiResponse<Map<String, Object>> settings(HttpServletRequest request) {
        requireAdmin(request);
        return ApiResponse.success(settingService.current());
    }

    @PutMapping("/settings")
    public ApiResponse<Map<String, Object>> updateSettings(HttpServletRequest request, @RequestBody Map<String, Object> body) {
        long adminId = requireAdmin(request);
        adminService.requireSuperAdmin(adminId);
        if (!body.containsKey("enabled")) throw new BusinessException(400, "enabled is required");
        boolean enabled = Boolean.TRUE.equals(body.get("enabled"));
        // 服务内部处理“值未变化则不写日志”的短路，并写入管理操作日志。
        return ApiResponse.success(settingService.update(enabled, adminId, request.getRemoteAddr(), request.getHeader("User-Agent")));
    }

    @GetMapping
    public ApiResponse<Map<String, Object>> list(HttpServletRequest request, @RequestParam Map<String, String> params) {
        requireAdmin(request);
        return ApiResponse.success(ticketService.adminList(new java.util.LinkedHashMap<>(params)));
    }

    @GetMapping("/{id}")
    public ApiResponse<Map<String, Object>> detail(HttpServletRequest request, @PathVariable long id) {
        requireAdmin(request);
        return ApiResponse.success(ticketService.adminDetail(id));
    }

    @GetMapping("/{id}/messages")
    public ApiResponse<Map<String, Object>> messages(HttpServletRequest request, @PathVariable long id,
                                                     @RequestParam(defaultValue = "1") int page,
                                                     @RequestParam(defaultValue = "20") int size) {
        requireAdmin(request);
        return ApiResponse.success(ticketService.adminMessages(id, page, size));
    }

    @GetMapping("/{id}/events")
    public ApiResponse<Map<String, Object>> events(HttpServletRequest request, @PathVariable long id) {
        requireAdmin(request);
        return ApiResponse.success(ticketService.adminEvents(id));
    }

    @PostMapping("/{id}/reply")
    public ApiResponse<Map<String, Object>> reply(HttpServletRequest request, @PathVariable long id,
                                                  @RequestBody Map<String, Object> body) {
        long adminId = requireAdmin(request);
        Map<String, Object> after = ticketService.adminReply(adminId, id, body);
        logAction(request, adminId, Boolean.TRUE.equals(body.get("internal")) ? "ticket_internal_note" : "ticket_reply", id, body);
        return ApiResponse.success(after);
    }

    @PutMapping("/{id}/status")
    public ApiResponse<Map<String, Object>> changeStatus(HttpServletRequest request, @PathVariable long id,
                                                         @RequestBody Map<String, Object> body) {
        long adminId = requireAdmin(request);
        Map<String, Object> after = ticketService.changeStatus(adminId, id, body);
        logAction(request, adminId, "ticket_status_change", id,
                Map.of("version", body.getOrDefault("version", 0), "to", body.getOrDefault("status", "")));
        return ApiResponse.success(after);
    }

    @PutMapping("/{id}/assignee")
    public ApiResponse<Map<String, Object>> assignee(HttpServletRequest request, @PathVariable long id,
                                                     @RequestBody Map<String, Object> body) {
        long adminId = requireAdmin(request);
        Map<String, Object> after = ticketService.assign(adminId, id, body);
        logAction(request, adminId, "ticket_assign", id, body);
        return ApiResponse.success(after);
    }

    @PutMapping("/{id}/priority")
    public ApiResponse<Map<String, Object>> priority(HttpServletRequest request, @PathVariable long id,
                                                     @RequestBody Map<String, Object> body) {
        long adminId = requireAdmin(request);
        Map<String, Object> after = ticketService.setPriority(adminId, id, body);
        logAction(request, adminId, "ticket_priority", id, body);
        return ApiResponse.success(after);
    }

    @PutMapping("/{id}/pinned")
    public ApiResponse<Map<String, Object>> pinned(HttpServletRequest request, @PathVariable long id,
                                                   @RequestBody Map<String, Object> body) {
        long adminId = requireAdmin(request);
        Map<String, Object> after = ticketService.setPinned(adminId, id, body);
        logAction(request, adminId, "ticket_pin", id, body);
        return ApiResponse.success(after);
    }

    @PostMapping("/{id}/hide")
    public ApiResponse<Map<String, Object>> hide(HttpServletRequest request, @PathVariable long id,
                                                 @RequestBody Map<String, Object> body) {
        long adminId = requireAdmin(request);
        Map<String, Object> after = ticketService.setHidden(adminId, id, body, true);
        logAction(request, adminId, "ticket_hide", id, body);
        return ApiResponse.success(after);
    }

    @PostMapping("/{id}/unhide")
    public ApiResponse<Map<String, Object>> unhide(HttpServletRequest request, @PathVariable long id,
                                                   @RequestBody Map<String, Object> body) {
        long adminId = requireAdmin(request);
        Map<String, Object> after = ticketService.setHidden(adminId, id, body, false);
        logAction(request, adminId, "ticket_restore", id, body);
        return ApiResponse.success(after);
    }

    /** 将 sourceId 合并入 {id} 主工单。 */
    @PostMapping("/{id}/merge")
    public ApiResponse<Map<String, Object>> merge(HttpServletRequest request, @PathVariable long id,
                                                  @RequestBody Map<String, Object> body) {
        long adminId = requireAdmin(request);
        Map<String, Object> after = ticketService.merge(adminId, id, body);
        logAction(request, adminId, "ticket_merge", id,
                Map.of("sourceId", body.getOrDefault("sourceId", ""), "note", body.getOrDefault("note", "")));
        return ApiResponse.success(after);
    }

    /** {id} 为源工单：取消其合并关系并回到待处理。 */
    @PostMapping("/{id}/unmerge")
    public ApiResponse<Map<String, Object>> unmerge(HttpServletRequest request, @PathVariable long id,
                                                    @RequestBody Map<String, Object> body) {
        long adminId = requireAdmin(request);
        Map<String, Object> after = ticketService.unmerge(adminId, id, body);
        logAction(request, adminId, "ticket_unmerge", id, body);
        return ApiResponse.success(after);
    }

    @PostMapping("/attachments")
    public ApiResponse<Map<String, Object>> upload(HttpServletRequest request,
                                                   @RequestParam("file") MultipartFile file) {
        long adminId = requireAdmin(request);
        rateLimiter.checkAndRecord(adminId, "admin_upload", properties.getReplyPerMinute(), Integer.MAX_VALUE);
        TicketAttachmentStorage.StoredAttachment stored = storage.store("admin", adminId, file);
        return ApiResponse.success(Map.of(
                "id", stored.id(),
                "mimeType", stored.mimeType(),
                "sizeBytes", stored.sizeBytes(),
                "width", stored.width(),
                "height", stored.height(),
                "name", stored.originalName()));
    }

    @GetMapping("/attachments/{id}")
    public ResponseEntity<byte[]> read(HttpServletRequest request, @PathVariable long id) {
        requireAdmin(request);
        Map<String, Object> attachment = storage.loadAttachment(id);
        if (attachment == null) throw new BusinessException(404, "attachment not found");
        byte[] bytes = storage.readFile(String.valueOf(attachment.get("storage_key")));
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType(String.valueOf(attachment.get("mime_type"))))
                .body(bytes);
    }

    /** 管理端可删除未绑定临时附件；已绑定附件走隐藏流程，不提供物理删除。 */
    @DeleteMapping("/attachments/{id}")
    public ApiResponse<Map<String, Object>> delete(HttpServletRequest request, @PathVariable long id) {
        long adminId = requireAdmin(request);
        storage.deleteTemporary("admin", adminId, id);
        Map<String, Object> attachment = storage.loadAttachment(id);
        if (attachment != null) storage.deleteFileQuietly(String.valueOf(attachment.get("storage_key")));
        logAction(request, adminId, "ticket_attachment_delete", id, Map.of());
        return ApiResponse.success(Map.of("ok", true));
    }

    private long requireAdmin(HttpServletRequest request) {
        return authService.requireAdmin(request.getHeader("Authorization"));
    }

    private void logAction(HttpServletRequest request, long adminId, String action, long ticketId, Map<String, Object> data) {
        adminService.writeAdminOperationLog(adminId, action, "ticket", ticketId,
                Map.of(), data, request.getRemoteAddr(), request.getHeader("User-Agent"));
    }
}
