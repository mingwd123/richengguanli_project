package com.dayliane.ticket;

import com.dayliane.admin.AdminService;
import com.dayliane.auth.AuthService;
import com.dayliane.common.BusinessException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.ActiveProfiles;

import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** 工单模块边界覆盖（计划 §14 验收场景）。 */
@SpringBootTest
@ActiveProfiles("test")
class TicketTests {

    private static final byte[] PNG_1PX = Base64.getDecoder().decode(
            "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mP8z8BQDwAEhQGAhKmMIQAAAABJRU5ErkJggg==");

    @Autowired TicketService ticketService;
    @Autowired TicketSettingService settingService;
    @Autowired TicketAttachmentStorage storage;
    @Autowired TicketRateLimiter rateLimiter;
    @Autowired AuthService authService;
    @Autowired AdminService adminService;
    @Autowired JdbcTemplate jdbc;

    private long superAdmin;

    @BeforeEach
    void setUp() {
        rateLimiter.reset();
        settingService.update(false, 1L);
        superAdmin = ensureSuperAdmin("ticket-root");
        ensureSuperAdmin("ticket-admin");
    }

    // ==================== 开关 ====================

    @Test
    void moduleIsDisabledByDefaultAndBlocksUserOperations() {
        assertThat(settingService.isEnabled()).isFalse();
        assertThat(settingService.current().get("enabled")).isEqualTo(false);

        long user = newUser("15900001001");
        Map<String, Object> req = baseCreateRequest();
        assertThatThrownBy(() -> ticketService.create(user, req))
                .isInstanceOf(BusinessException.class)
                .hasMessage(TicketSettingService.DISABLED_MESSAGE);

        settingService.update(true, superAdmin);
        Map<String, Object> created = ticketService.create(user, req);
        assertThat(created.get("status")).isEqualTo("open");

        settingService.update(false, superAdmin);
        assertThatThrownBy(() -> ticketService.reply(user, ticketId(created), baseReplyRequest()))
                .isInstanceOf(BusinessException.class)
                .hasMessage(TicketSettingService.DISABLED_MESSAGE);
    }

    @Test
    void onlySuperAdminCanToggleTheSwitch() {
        jdbc.update("insert into admin_user (username,password_hash,role,status) values ('ticket-normal','x','admin','active')");
        long normal = adminId("ticket-normal");
        assertThatThrownBy(() -> adminService.requireSuperAdmin(normal)).isInstanceOf(BusinessException.class);
        adminService.requireSuperAdmin(superAdmin);

        settingService.update(true, superAdmin);
        Map<String, Object> log = jdbc.queryForMap(
                "select action,target_type from admin_operation_log where admin_id=? and action='set_ticket_enabled' order by id desc limit 1",
                superAdmin);
        assertThat(log.get("target_type")).isEqualTo("ticket_setting");
    }

    // ==================== 创建、校验与公开可见性 ====================

    @Test
    void createValidatesFieldsAndSharesTheTicketPublicly() {
        enable();
        long author = newUser("15900001002");
        long other = newUser("15900001003");

        assertThatThrownBy(() -> ticketService.create(author, withTitle(baseCreateRequest(), "短")))
                .isInstanceOf(BusinessException.class).hasMessageContaining("title");
        assertThatThrownBy(() -> {
            Map<String, Object> req = baseCreateRequest();
            req.put("category", "hacking");
            ticketService.create(author, req);
        }).isInstanceOf(BusinessException.class).hasMessageContaining("category");
        assertThatThrownBy(() -> {
            Map<String, Object> req = baseCreateRequest();
            req.put("description", "太短");
            ticketService.create(author, req);
        }).isInstanceOf(BusinessException.class).hasMessageContaining("description");

        Map<String, Object> created = ticketService.create(author, baseCreateRequest());
        long id = ticketId(created);
        assertThat(String.valueOf(created.get("ticketNo"))).startsWith("TK-").hasSize(11);

        Map<String, Object> seenByOther = ticketService.detail(id, other);
        assertThat(seenByOther.get("title")).isEqualTo(created.get("title"));
        assertThat(String.valueOf(seenByOther.get("authorName"))).contains("Progress");
        assertThat(viewer(seenByOther)).containsEntry("isAuthor", false);
        assertThat(viewer(created)).containsEntry("isAuthor", true);
        // 作者创建后默认关注。
        assertThat(viewer(created)).containsEntry("following", true);
    }

    @Test
    void listSupportsSearchFiltersAndViews() {
        enable();
        long author = newUser("15900001004");
        long other = newUser("15900001005");
        Map<String, Object> first = ticketService.create(author, withTitle(baseCreateRequest(), "日程崩溃的问题反馈"));
        Map<String, Object> secondReq = withTitle(baseCreateRequest(), "疲劳评估显示问题");
        secondReq.put("module", "fatigue");
        secondReq.put("category", "display");
        Map<String, Object> second = ticketService.create(other, secondReq);

        ticketService.follow(other, ticketId(second));

        Map<String, Object> mine = ticketService.list(author, Map.of("view", "mine"));
        assertThat(countItems(mine)).isEqualTo(1);
        Map<String, Object> following = ticketService.list(other, Map.of("view", "following"));
        assertThat(countItems(following)).isEqualTo(1);

        Map<String, Object> byKeyword = ticketService.list(author, Map.of("keyword", "疲劳评估"));
        assertThat(countItems(byKeyword)).isEqualTo(1);

        Map<String, Object> byStatus = ticketService.list(author, Map.of("status", "resolved"));
        assertThat(countItems(byStatus)).isZero();

        Map<String, Object> byModule = ticketService.list(author, Map.of("module", "fatigue"));
        assertThat(countItems(byModule)).isEqualTo(1);
        assertThat(ticketId(first)).isPositive();
    }

    // ==================== 附件 ====================

    @Test
    void attachmentLifecycleCoversAuthAndValidation() {
        enable();
        long author = newUser("15900001006");
        long other = newUser("15900001007");

        TicketAttachmentStorage.StoredAttachment stored = upload(author, "screen.png", PNG_1PX);
        assertThat(stored.width()).isEqualTo(1);

        // 伪造扩展名：内容不是图片。
        assertThatThrownBy(() -> upload(author, "fake.png", "hello".getBytes()))
                .isInstanceOf(BusinessException.class).hasMessageContaining("content");
        // SVG 与未知扩展名直接拒绝。
        assertThatThrownBy(() -> upload(author, "vector.svg", PNG_1PX))
                .isInstanceOf(BusinessException.class).hasMessageContaining("allowed");

        // 超过 10MiB 拒绝；恰好 10MiB 通过大小关卡（后续倒在解码校验上）。
        byte[] tooBig = new byte[10 * 1024 * 1024 + 1];
        System.arraycopy(PNG_1PX, 0, tooBig, 0, PNG_1PX.length);
        assertThatThrownBy(() -> upload(author, "big.png", tooBig))
                .isInstanceOf(BusinessException.class).hasMessageContaining("10MB");
        byte[] exactBig = new byte[10 * 1024 * 1024];
        System.arraycopy(PNG_1PX, 0, exactBig, 0, PNG_1PX.length);
        // §14.3：恰好 10MiB 且格式合法 → 允许（PNG 解码器忽略 IEND 之后的冗余字节）。
        assertThat(upload(author, "exact.png", exactBig).sizeBytes()).isEqualTo(10 * 1024 * 1024);
        // 内容损坏的图片在解码校验处拒绝。
        byte[] broken = new byte[64];
        System.arraycopy(PNG_1PX, 0, broken, 0, 8);
        assertThatThrownBy(() -> upload(author, "broken.png", broken))
                .isInstanceOf(BusinessException.class).hasMessageContaining("decodable");

        // 未绑定附件他人不可读，作者本人可读。
        Map<String, Object> row = storage.loadAttachment(stored.id());
        assertThat(row.get("state")).isEqualTo("temporary");
        long finalAuthor = author;
        long finalOther = other;
        long attachmentId = stored.id();
        assertThat(readableByUser(attachmentId, finalOther)).isFalse();
        assertThat(readableByUser(attachmentId, finalAuthor)).isTrue();

        // 创建工单绑定后，所有用户可读；超过 5 张拒绝。
        Map<String, Object> created = ticketService.create(author, withAttachments(baseCreateRequest(), List.of(stored.id())));
        long ticketId = ticketId(created);
        assertThat(readableByUser(attachmentId, finalOther)).isTrue();

        List<Long> six = new ArrayList<>();
        for (int i = 0; i < 6; i++) six.add(upload(author, "multi-" + i + ".png", PNG_1PX).id());
        Map<String, Object> req = withAttachments(baseCreateRequest(), six);
        assertThatThrownBy(() -> ticketService.create(author, req))
                .isInstanceOf(BusinessException.class).hasMessageContaining("too many images");

        // 不能绑定他人的临时附件，也不能删除他人的附件。
        TicketAttachmentStorage.StoredAttachment foreign = upload(author, "mine.png", PNG_1PX);
        assertThatThrownBy(() -> ticketService.create(other, withAttachments(baseCreateRequest(), List.of(foreign.id()))))
                .isInstanceOf(BusinessException.class).hasMessageContaining("another uploader");
        assertThatThrownBy(() -> storage.deleteTemporary("user", other, foreign.id()))
                .isInstanceOf(BusinessException.class).hasMessageContaining("another uploader");
        // 已绑定附件不允许删除。
        assertThatThrownBy(() -> storage.deleteTemporary("user", author, attachmentId))
                .isInstanceOf(BusinessException.class).hasMessageContaining("bound");
    }

    @Test
    void cleanupRemovesExpiredTempAttachmentsOnly() {
        enable();
        long author = newUser("15900001008");
        TicketAttachmentStorage.StoredAttachment fresh = upload(author, "fresh.png", PNG_1PX);
        TicketAttachmentStorage.StoredAttachment expired = upload(author, "expired.png", PNG_1PX);
        jdbc.update("update ticket_attachment set created_at = ? where id=?",
                java.sql.Timestamp.from(java.time.Instant.now().minus(java.time.Duration.ofHours(25))), expired.id());

        storage.cleanupExpiredTempAttachments();

        assertThat(storage.loadAttachment(expired.id())).isNull();
        assertThat(storage.loadAttachment(fresh.id())).isNotNull();

        // 绑定后即使久置也不被清理。
        Map<String, Object> created = ticketService.create(author, withAttachments(baseCreateRequest(), List.of(fresh.id())));
        jdbc.update("update ticket_attachment set created_at = ? where id=?",
                java.sql.Timestamp.from(java.time.Instant.now().minus(java.time.Duration.ofHours(48))), fresh.id());
        storage.cleanupExpiredTempAttachments();
        assertThat(storage.loadAttachment(fresh.id())).isNotNull();
        assertThat(ticketId(created)).isPositive();
    }

    // ==================== 状态机与作者操作 ====================

    @Test
    void statusMachineCoversReporterSupplementConfirmWithdrawAndReopen() {
        enable();
        long author = newUser("15900001009");
        long admin = adminId("ticket-admin");
        long id = ticketId(ticketService.create(author, baseCreateRequest()));
        long version = currentVersion(id);

        // 待补充必须说明缺什么。
        assertThatThrownBy(() -> ticketService.changeStatus(admin, id, Map.of("status", "waiting_reporter", "version", (Object) version)))
                .isInstanceOf(BusinessException.class).hasMessageContaining("information");
        ticketService.changeStatus(admin, id, Map.of("status", "waiting_reporter", "note", "请补充浏览器版本与截图", "version", (Object) version));

        // 原作者回复自动回到处理中；其他用户回复不改变状态。
        ticketService.reply(author, id, baseReplyRequest());
        assertThat(ticketService.adminDetail(id).get("status")).isEqualTo("in_progress");

        // 标记解决必须有解决摘要。
        long v2 = currentVersion(id);
        assertThatThrownBy(() -> ticketService.changeStatus(admin, id, Map.of("status", "resolved", "version", (Object) v2)))
                .isInstanceOf(BusinessException.class).hasMessageContaining("resolution");
        ticketService.changeStatus(admin, id, Map.of("status", "resolved", "resolution", "已在设置中开启该功能并更新版本", "version", (Object) v2));
        assertThat(ticketService.adminDetail(id).get("status")).isEqualTo("resolved");

        // 原作者确认解决 → 关闭。
        ticketService.confirmResolved(author, id);
        assertThat(ticketService.adminDetail(id).get("status")).isEqualTo("closed");
        assertThat(ticketService.detail(id, author).get("closeReason")).isEqualTo("resolved");

        // 因“已解决”关闭可由作者申请重开。
        ticketService.reopenByAuthor(author, id, Map.of("reason", "更新后问题仍然存在，请再帮忙看看"));
        assertThat(ticketService.adminDetail(id).get("status")).isEqualTo("in_progress");

        // 作者撤回。
        ticketService.withdraw(author, id, Map.of("note", "是自己的配置问题"));
        assertThat(ticketService.detail(id, author).get("closeReason")).isEqualTo("user_withdrawn");
        ticketService.reopenByAuthor(author, id, Map.of("reason", "经过复测问题确实存在"));
        assertThat(ticketService.adminDetail(id).get("status")).isEqualTo("in_progress");

        // 已关闭工单不允许普通回复。
        ticketService.changeStatus(admin, id, Map.of("status", "closed", "closeReason", "insufficient_info",
                "note", "长期缺少必要的复现信息", "version", (Object) currentVersion(id)));
        assertThatThrownBy(() -> ticketService.reply(author, id, baseReplyRequest()))
                .isInstanceOf(BusinessException.class).hasMessageContaining("closed");
    }

    @Test
    void adminConcurrentUpdatesReportConflictInsteadOfOverwrite() {
        enable();
        long author = newUser("15900001010");
        long admin = adminId("ticket-admin");
        long id = ticketId(ticketService.create(author, baseCreateRequest()));
        long version = currentVersion(id);

        ticketService.changeStatus(admin, id, Map.of("status", "in_progress", "version", (Object) version));
        assertThatThrownBy(() -> ticketService.changeStatus(admin, id, Map.of("status", "resolved",
                "resolution", "另一个管理员已经先处理完成的问题", "version", (Object) version)))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("please refresh");
    }

    // ==================== 合并 ====================

    @Test
    void mergeTransfersFollowersAndCanBeUndone() {
        enable();
        long authorA = newUser("15900001011");
        long authorB = newUser("15900001012");
        long admin = adminId("ticket-admin");
        long main = ticketId(ticketService.create(authorA, baseCreateRequest()));
        long source = ticketId(ticketService.create(authorB, withTitle(baseCreateRequest(), "重复提交的同一个问题")));
        ticketService.follow(authorA, source);

        // 自我合并 / 目标已有重复时拒绝。
        assertThatThrownBy(() -> ticketService.merge(admin, source, Map.of("sourceId", (Object) source, "note", "self merge")))
                .isInstanceOf(BusinessException.class);
        ticketService.merge(admin, main, Map.of("sourceId", (Object) source, "note", "与主工单为同一问题"));
        assertThat(ticketService.adminDetail(source).get("status")).isEqualTo("closed");
        assertThat(ticketService.detail(source, authorB).get("duplicateOfId")).isEqualTo(main);
        // 源作者被带去关注主工单。
        assertThat(viewer(ticketService.detail(main, authorB))).containsEntry("following", true);

        // 主工单已接收过重复工单后，不能再作为合并目标。
        long third = ticketId(ticketService.create(authorA, withTitle(baseCreateRequest(), "另一条重复的反馈")));
        assertThatThrownBy(() -> ticketService.merge(admin, main, Map.of("sourceId", (Object) third, "note", "再一次合并")))
                .isInstanceOf(BusinessException.class).hasMessageContaining("already received");

        ticketService.unmerge(admin, source, Map.of("note", "合并选错了主工单"));
        assertThat(ticketService.adminDetail(source).get("status")).isEqualTo("open");
        assertThat(ticketService.adminDetail(source).get("duplicateOfId")).isNull();
    }

    // ==================== 幂等 ====================

    @Test
    void idempotencyKeysMakeCreateAndReplySafeToRetry() {
        enable();
        long author = newUser("15900001013");
        Map<String, Object> req = baseCreateRequest();
        req.put("idempotencyKey", "client-key-1");
        Map<String, Object> first = ticketService.create(author, req);
        Map<String, Object> second = ticketService.create(author, req);
        assertThat(ticketId(second)).isEqualTo(ticketId(first));

        Map<String, Object> different = baseCreateRequest();
        different.put("idempotencyKey", "client-key-1");
        different.put("description", "内容不同的同键请求，应当被拒绝而不是创建新工单");
        assertThatThrownBy(() -> ticketService.create(author, different))
                .isInstanceOf(BusinessException.class).hasMessageContaining("idempotency");
    }

    // ==================== 限流 ====================

    @Test
    void creationIsRateLimitedPerUser() {
        enable();
        long author = newUser("15900001014");
        ticketService.create(author, withTitle(baseCreateRequest(), "第一条限流测试工单"));
        ticketService.create(author, withTitle(baseCreateRequest(), "第二条限流测试工单"));
        assertThatThrownBy(() -> ticketService.create(author, withTitle(baseCreateRequest(), "第三条限流测试工单")))
                .isInstanceOf(BusinessException.class).hasMessageContaining("too many requests");
        // 其他用户不受影响。
        long other = newUser("15900001015");
        assertThat(ticketId(ticketService.create(other, baseCreateRequest()))).isPositive();
    }

    // ==================== 通知与隐藏 ====================

    @Test
    void notificationsReachAuthorAndFollowersWithoutActorSelfNotice() {
        enable();
        long author = newUser("15900001016");
        long follower = newUser("15900001017");
        long admin = adminId("ticket-admin");
        long id = ticketId(ticketService.create(author, baseCreateRequest()));
        ticketService.follow(follower, id);
        jdbc.update("delete from notification where user_id in (?,?)", author, follower);

        ticketService.adminReply(admin, id, Map.of("content", "已收到，正在排查"));
        assertThat(notificationsOf(author)).isEqualTo(1);
        assertThat(notificationsOf(follower)).isEqualTo(1);

        // 内部备注不产生用户通知。
        ticketService.adminReply(admin, id, Map.of("content", "内部：疑似与近期发布有关", "internal", true));
        assertThat(notificationsOf(author)).isEqualTo(1);

        // 其他用户的普通回复不推送关注者。
        long other = newUser("15900001018");
        ticketService.reply(other, id, baseReplyRequest());
        assertThat(notificationsOf(author)).isEqualTo(1);

        // 状态变化通知作者与关注者。
        ticketService.changeStatus(admin, id, Map.of("status", "resolved",
                "resolution", "问题已在新版本中修复，请更新后验证", "version", (Object) currentVersion(id)));
        assertThat(notificationsOf(author)).isEqualTo(2);
        assertThat(notificationsOf(follower)).isEqualTo(2);
    }

    @Test
    void hiddenTicketsBecomeUnavailableToUsersButStayVisibleToAdmins() {
        enable();
        long author = newUser("15900001019");
        long other = newUser("15900001020");
        long admin = adminId("ticket-admin");
        TicketAttachmentStorage.StoredAttachment stored = upload(author, "hidden.png", PNG_1PX);
        long id = ticketId(ticketService.create(author, withAttachments(baseCreateRequest(), List.of(stored.id()))));

        ticketService.setHidden(admin, id, Map.of("reason", "包含不适宜内容", "version", (Object) currentVersion(id)), true);
        assertThatThrownBy(() -> ticketService.detail(id, other)).isInstanceOf(BusinessException.class);
        assertThat(readableByUser(stored.id(), other)).isFalse();
        assertThat(ticketService.adminDetail(id).get("hiddenAt")).isNotNull();

        ticketService.setHidden(admin, id, Map.of("version", (Object) currentVersion(id)), false);
        assertThat(ticketService.detail(id, other).get("id")).isEqualTo(id);
        assertThat(readableByUser(stored.id(), other)).isTrue();
    }

    @Test
    void sameIssueIsIdempotentAndAutoFollows() {
        enable();
        long author = newUser("15900001021");
        long other = newUser("15900001022");
        long id = ticketId(ticketService.create(author, baseCreateRequest()));

        ticketService.markSameIssue(other, id);
        ticketService.markSameIssue(other, id);
        assertThat(ticketService.detail(id, other).get("reactionCount")).isEqualTo(1L);
        assertThat(viewer(ticketService.detail(id, other))).containsEntry("following", true);

        ticketService.unmarkSameIssue(other, id);
        assertThat(ticketService.detail(id, other).get("reactionCount")).isEqualTo(0L);
        assertThat(viewer(ticketService.detail(id, other))).containsEntry("following", true);
    }

    @Test
    void internalNotesAreHiddenFromUsersButVisibleToAdmins() {
        enable();
        long author = newUser("15900001023");
        long admin = adminId("ticket-admin");
        long id = ticketId(ticketService.create(author, baseCreateRequest()));
        ticketService.adminReply(admin, id, Map.of("content", "这条是内部判断，不要外发", "internal", true));

        Map<String, Object> userMessages = ticketService.messages(id, author, 1, 20);
        assertThat(countItems(userMessages)).isZero();
        Map<String, Object> adminMessages = ticketService.adminMessages(id, 1, 20);
        assertThat(countItems(adminMessages)).isEqualTo(1);
    }

    // ==================== 辅助 ====================

    private void enable() {
        settingService.update(true, superAdmin);
    }

    private long newUser(String phone) {
        return authService.register(phone, "Abc12345", "Progress User", "Asia/Shanghai");
    }

    /** 确保存在指定用户名的超级管理员并返回其 id（幂等）。 */
    private long ensureSuperAdmin(String username) {
        jdbc.update("merge into admin_user (username,password_hash,role,status) key(username) values (?,?, 'super_admin', 'active')",
                username, "x");
        return adminId(username);
    }

    private long adminId(String username) {
        return jdbc.queryForObject("select id from admin_user where username=?", Long.class, username);
    }

    private Map<String, Object> baseCreateRequest() {
        Map<String, Object> req = new LinkedHashMap<>();
        req.put("title", "每日进度提交后负荷没有变化");
        req.put("category", "bug");
        req.put("module", "daily_progress");
        req.put("description", "提交每日进度之后个人完成负荷与报告数字都没有变化，请帮忙排查。");
        req.put("steps", "1. 打开工单详情 2. 提交进度");
        req.put("expectedResult", "负荷应当增加");
        return req;
    }

    private Map<String, Object> withTitle(Map<String, Object> req, String title) {
        req.put("title", title);
        return req;
    }

    private Map<String, Object> withAttachments(Map<String, Object> req, List<Long> ids) {
        req.put("attachmentIds", ids);
        return req;
    }

    private Map<String, Object> baseReplyRequest() {
        Map<String, Object> req = new LinkedHashMap<>();
        req.put("content", "补充：在手机浏览器上也能复现。");
        return req;
    }

    private TicketAttachmentStorage.StoredAttachment upload(long userId, String filename, byte[] bytes) {
        MockMultipartFile file = new MockMultipartFile("file", filename, "image/png", bytes);
        return storage.store("user", userId, file);
    }

    /** 直接调用读取接口背后的校验逻辑，等价于 GET /attachments/{id} 的权限判断。 */
    private boolean readableByUser(long attachmentId, long userId) {
        Map<String, Object> attachment = storage.loadAttachment(attachmentId);
        if (attachment == null) return false;
        boolean owner = "user".equals(String.valueOf(attachment.get("uploader_type")))
                && ((Number) attachment.get("uploader_id")).longValue() == userId;
        if (owner) return true;
        if (!settingService.isEnabled()) return false;
        if (!"bound".equals(String.valueOf(attachment.get("state")))) return false;
        Long ticketId = attachment.get("ticket_id") == null ? null : ((Number) attachment.get("ticket_id")).longValue();
        return ticketId == null || !ticketService.isHidden(ticketId);
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> viewer(Map<String, Object> view) {
        return (Map<String, Object>) view.get("viewer");
    }

    private long ticketId(Map<String, Object> view) {
        return ((Number) view.get("id")).longValue();
    }

    private long currentVersion(long ticketId) {
        return ((Number) ticketService.adminDetail(ticketId).get("version")).longValue();
    }

    private int countItems(Map<String, Object> result) {
        return ((List<?>) result.get("items")).size();
    }

    private int notificationsOf(long userId) {
        Integer count = jdbc.queryForObject(
                "select count(*) from notification where user_id=? and type='ticket' and deleted_at is null",
                Integer.class, userId);
        return count == null ? 0 : count;
    }
}
