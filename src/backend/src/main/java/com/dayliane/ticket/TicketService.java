package com.dayliane.ticket;

import com.dayliane.common.BusinessException;
import com.dayliane.notification.NotificationService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 工单模块核心逻辑（计划 §三/§四/§五/§八/§九）。
 *
 * 一致性要点：
 * - 所有状态变更统一走 {@link #changeStatusCore}，校验状态机、权限与必填原因；
 * - 管理端状态变更使用乐观版本号，冲突返回 409；
 * - 状态修改、事件写入与通知写入在同一事务内完成，通知失败不影响主流程；
 * - 公开响应只由显式字段组装，绝不透出数据库整行。
 */
@Service
public class TicketService {

    private static final Logger log = LoggerFactory.getLogger(TicketService.class);
    private static final ObjectMapper objectMapper = new ObjectMapper();

    public static final Set<String> CATEGORIES = Set.of("bug", "display", "data", "account", "question", "suggestion", "other");
    public static final Set<String> MODULES = Set.of("schedule", "team_task", "daily_progress", "fatigue",
            "notification", "ai", "account", "desktop", "other");
    private static final Set<String> PRIORITIES = Set.of("low", "normal", "high", "urgent");
    private static final Set<String> STATUSES = Set.of("open", "in_progress", "waiting_reporter", "resolved", "closed");
    private static final Set<String> USER_REOPENABLE_REASONS = Set.of("resolved", "user_withdrawn");
    private static final int MAX_IMAGES_PER_CONTENT = 5;

    private final JdbcTemplate jdbc;
    private final TicketSettingService settingService;
    private final TicketAttachmentStorage storage;
    private final TicketRateLimiter rateLimiter;
    private final TicketProperties properties;
    private final NotificationService notificationService;

    public TicketService(JdbcTemplate jdbc, TicketSettingService settingService, TicketAttachmentStorage storage,
                         TicketRateLimiter rateLimiter, TicketProperties properties, NotificationService notificationService) {
        this.jdbc = jdbc;
        this.settingService = settingService;
        this.storage = storage;
        this.rateLimiter = rateLimiter;
        this.properties = properties;
        this.notificationService = notificationService;
    }

    // ==================== 用户端：查询 ====================

    public Map<String, Object> list(long userId, Map<String, Object> params) {
        settingService.requireEnabled();
        rateLimiter.checkAndRecord(userId, "read", properties.getReadPerMinute(), Integer.MAX_VALUE);

        String view = text(params.get("view"), "all");
        String keyword = text(params.get("keyword"), "");
        String status = text(params.get("status"), "");
        String category = text(params.get("category"), "");
        String module = text(params.get("module"), "");
        String sort = text(params.get("sort"), "activity");
        int page = Math.max(intValue(params.get("page"), 1), 1);
        int size = Math.min(Math.max(intValue(params.get("size"), 20), 1), 100);

        StringBuilder where = new StringBuilder(" t.hidden_at is null ");
        List<Object> args = new ArrayList<>();
        switch (view) {
            case "mine" -> where.append(" and t.author_id=? ");
            case "following" -> where.append(" and exists (select 1 from ticket_follow f where f.ticket_id=t.id and f.user_id=?) ");
            default -> { /* all：所有已登录有效用户可见 */ }
        }
        if ("mine".equals(view) || "following".equals(view)) args.add(userId);
        if (!keyword.isBlank()) {
            where.append(" and (t.title like ? escape '\\' or t.ticket_no like ? escape '\\') ");
            String like = "%" + escapeLike(keyword.trim()) + "%";
            args.add(like);
            args.add(like);
        }
        if (!status.isBlank()) {
            requireIn(status, STATUSES, "status");
            where.append(" and t.status=? ");
            args.add(status);
        }
        if (!category.isBlank()) {
            requireIn(category, CATEGORIES, "category");
            where.append(" and t.category=? ");
            args.add(category);
        }
        if (!module.isBlank()) {
            requireIn(module, MODULES, "module");
            where.append(" and t.module=? ");
            args.add(module);
        }
        String order = switch (sort) {
            case "newest" -> "t.created_at desc, t.id desc";
            case "reacted" -> "reaction_count desc, t.last_activity_at desc, t.id desc";
            default -> "t.last_activity_at desc, t.id desc";
        };

        String countSql = "select count(*) from ticket t where " + where;
        Integer total = jdbc.queryForObject(countSql, Integer.class, args.toArray());
        String listSql = "select t.id,t.ticket_no,t.title,t.category,t.module,t.status,t.priority,t.author_id,"
                + "t.pinned,t.created_at,t.updated_at,t.last_activity_at,t.duplicate_of_id,t.close_reason,"
                + "(select count(*) from ticket_message m where m.ticket_id=t.id and m.visibility='public' and m.hidden_at is null) as reply_count, "
                + "(select count(*) from ticket_reaction r where r.ticket_id=t.id and r.reaction_type='same_issue') as reaction_count "
                + "from ticket t where " + where
                + " order by t.pinned desc, " + order + " limit ? offset ?";
        List<Object> listArgs = new ArrayList<>(args);
        listArgs.add(size);
        listArgs.add((page - 1) * size);
        List<Map<String, Object>> rows = jdbc.queryForList(listSql, listArgs.toArray());

        List<Map<String, Object>> items = new ArrayList<>();
        for (Map<String, Object> row : rows) items.add(listItemView(row, userId));
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("items", items);
        result.put("total", total == null ? 0 : total);
        result.put("page", page);
        result.put("size", size);
        result.put("enabled", settingService.isEnabled());
        return result;
    }

    public Map<String, Object> detail(long ticketId, long userId) {
        settingService.requireEnabled();
        Map<String, Object> row = requireVisibleTicket(ticketId);
        return publicView(row, userId);
    }

    public Map<String, Object> messages(long ticketId, long userId, int page, int size) {
        settingService.requireEnabled();
        requireVisibleTicket(ticketId);
        page = Math.max(page, 1);
        size = Math.min(Math.max(size, 1), 50);
        List<Map<String, Object>> rows = jdbc.queryForList(
                "select m.id,m.actor_type,m.actor_id,m.content,m.created_at from ticket_message m "
                        + "where m.ticket_id=? and m.visibility='public' and m.hidden_at is null "
                        + "order by m.created_at desc, m.id desc limit ? offset ?",
                ticketId, size, (long) (page - 1) * size);
        Integer total = jdbc.queryForObject(
                "select count(*) from ticket_message where ticket_id=? and visibility='public' and hidden_at is null",
                Integer.class, ticketId);
        List<Map<String, Object>> items = new ArrayList<>();
        for (Map<String, Object> row : rows) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("id", row.get("id"));
            item.put("actorType", row.get("actor_type"));
            item.put("actorId", row.get("actor_id"));
            item.put("actorName", actorDisplayName(String.valueOf(row.get("actor_type")),
                    ((Number) row.get("actor_id")).longValue()));
            item.put("isAdmin", "admin".equals(row.get("actor_type")));
            item.put("content", row.get("content"));
            item.put("createdAt", iso(row.get("created_at")));
            item.put("attachments", boundAttachmentViews(ticketId, ((Number) row.get("id")).longValue()));
            items.add(item);
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("items", items);
        result.put("total", total == null ? 0 : total);
        result.put("page", page);
        result.put("size", size);
        return result;
    }

    public Map<String, Object> events(long ticketId, long userId) {
        settingService.requireEnabled();
        requireVisibleTicket(ticketId);
        List<Map<String, Object>> rows = jdbc.queryForList(
                "select action,from_status,to_status,note,actor_type,actor_id,created_at from ticket_event "
                        + "where ticket_id=? and visibility='public' order by created_at asc, id asc", ticketId);
        List<Map<String, Object>> items = new ArrayList<>();
        for (Map<String, Object> row : rows) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("action", row.get("action"));
            item.put("fromStatus", row.get("from_status"));
            item.put("toStatus", row.get("to_status"));
            item.put("note", row.get("note"));
            item.put("actorType", row.get("actor_type"));
            item.put("actorName", actorDisplayName(String.valueOf(row.get("actor_type")),
                    row.get("actor_id") == null ? 0 : ((Number) row.get("actor_id")).longValue()));
            item.put("createdAt", iso(row.get("created_at")));
            items.add(item);
        }
        return Map.of("items", items);
    }

    public Map<String, Object> viewerState(long ticketId, long userId) {
        Boolean following = !jdbc.queryForList("select 1 from ticket_follow where ticket_id=? and user_id=?",
                ticketId, userId).isEmpty();
        Boolean reacted = !jdbc.queryForList(
                "select 1 from ticket_reaction where ticket_id=? and user_id=? and reaction_type='same_issue'",
                ticketId, userId).isEmpty();
        Map<String, Object> state = new LinkedHashMap<>();
        state.put("following", following);
        state.put("reacted", reacted);
        state.put("isAuthor", isAuthor(ticketId, userId));
        return state;
    }

    // ==================== 用户端：写入 ====================

    @Transactional
    public Map<String, Object> create(long userId, Map<String, Object> req) {
        settingService.requireEnabled();
        String title = requiredText(req.get("title"), 5, 100, "title");
        String category = requiredEnum(req.get("category"), CATEGORIES, "category");
        String module = requiredEnum(req.get("module"), MODULES, "module");
        String description = requiredText(req.get("description"), 10, 5000, "description");
        String steps = optionalText(req.get("steps"), 2000, "steps");
        String expectedResult = optionalText(req.get("expectedResult"), 1000, "expectedResult");
        String pagePath = sanitizePagePath(text(req.get("pagePath"), ""));
        Map<String, Object> environment = environmentOf(req.get("environment"));
        List<Number> attachmentIds = attachmentIds(req.get("attachmentIds"));

        String idempotencyKey = text(req.get("idempotencyKey"), "");
        String hash = requestHash(title, category, module, description, steps, expectedResult, pagePath, attachmentIds);
        // 幂等重放先于限流：网络结果不确定时的重试不应被限流挡住。
        Map<String, Object> replay = idempotencyLookup(userId, "create", idempotencyKey, hash);
        if (replay != null) return detail(((Number) replay.get("id")).longValue(), userId);
        rateLimiter.checkAndRecord(userId, "create", properties.getCreatePerMinute(), properties.getCreatePerDay());

        KeyHolder keyHolder = new GeneratedKeyHolder();
        // ticket_no 有唯一约束，先以随机占位号插入，拿到自增 id 后再格式化回写。
        String placeholder = "TMP" + java.util.UUID.randomUUID().toString().replace("-", "").substring(0, 12);
        jdbc.update(con -> {
            var ps = con.prepareStatement(
                    "insert into ticket (ticket_no,author_id,title,category,module,description,steps,expected_result,environment_json,page_path,status,priority) values (?,?,?,?,?,?,?,?,?,?,'open','normal')",
                    new String[]{"id"});
            ps.setString(1, placeholder);
            ps.setLong(2, userId);
            ps.setString(3, title);
            ps.setString(4, category);
            ps.setString(5, module);
            ps.setString(6, description);
            ps.setString(7, steps);
            ps.setString(8, expectedResult);
            ps.setString(9, environment.isEmpty() ? null : toJson(environment));
            ps.setString(10, pagePath.isBlank() ? null : pagePath);
            return ps;
        }, keyHolder);
        long ticketId = keyHolder.getKey() == null ? 0 : keyHolder.getKey().longValue();
        jdbc.update("update ticket set ticket_no=? where id=?", ticketNo(ticketId), ticketId);

        storage.bindAll(attachmentIds, "user", userId, ticketId, null, "ticket", MAX_IMAGES_PER_CONTENT);
        followTicket(ticketId, userId);
        insertEvent(ticketId, "user", userId, "created", null, "open", null, "public");
        notifyParticipants(ticketId, userId, "工单已提交",
                "你的工单 " + ticketNo(ticketId) + " 已提交，处理进展会通过通知更新");

        if (!idempotencyKey.isBlank()) idempotencySave(userId, "create", idempotencyKey, hash, ticketId);
        return detail(ticketId, userId);
    }

    @Transactional
    public Map<String, Object> reply(long userId, long ticketId, Map<String, Object> req) {
        settingService.requireEnabled();
        Map<String, Object> ticket = requireVisibleTicket(ticketId);
        String status = statusOf(ticket);
        if ("closed".equals(status)) throw new BusinessException(400, "closed tickets do not accept replies");

        String content = text(req.get("content"), "");
        List<Number> attachmentIds = attachmentIds(req.get("attachmentIds"));
        content = content.strip();
        if (content.length() > 3000) throw new BusinessException(400, "reply is limited to 3000 characters");
        if (content.isEmpty() && attachmentIds.isEmpty()) throw new BusinessException(400, "reply content is required");
        String idempotencyKey = text(req.get("idempotencyKey"), "");
        String hash = requestHash(content, attachmentIds);
        Map<String, Object> replay = idempotencyLookup(userId, "reply", idempotencyKey, hash);
        if (replay != null) return messages(ticketId, userId, 1, 20);
        rateLimiter.checkAndRecord(userId, "reply", properties.getReplyPerMinute(), properties.getReplyPerDay());

        boolean author = isAuthor(ticketId, userId);
        jdbc.update("insert into ticket_message (ticket_id,actor_type,actor_id,visibility,content) values (?,'user',?,'public',?)",
                ticketId, userId, content);
        Long messageId = jdbc.queryForObject(
                "select id from ticket_message where ticket_id=? and actor_type='user' and actor_id=? order by id desc limit 1",
                Long.class, ticketId, userId);
        storage.bindAll(attachmentIds, "user", userId, ticketId, messageId, "reply", MAX_IMAGES_PER_CONTENT);

        // 原提交者在“待补充”状态下回复，自动回到处理中；其他用户回复不改变状态。
        if (author && "waiting_reporter".equals(status)) {
            changeStatusCore(ticketId, "user", userId, "waiting_reporter", "in_progress",
                    text(req.get("note"), ""), null, null, null, true);
        }
        jdbc.update("update ticket set last_activity_at=utc_timestamp() where id=?", ticketId);
        insertEvent(ticketId, "user", userId, "replied", null, null, null, "public");

        if (!idempotencyKey.isBlank()) idempotencySave(userId, "reply", idempotencyKey, hash, ticketId);
        return messages(ticketId, userId, 1, 20);
    }

    @Transactional
    public void follow(long userId, long ticketId) {
        settingService.requireEnabled();
        requireVisibleTicket(ticketId);
        followTicket(ticketId, userId);
    }

    @Transactional
    public void unfollow(long userId, long ticketId) {
        settingService.requireEnabled();
        requireVisibleTicket(ticketId);
        jdbc.update("delete from ticket_follow where ticket_id=? and user_id=?", ticketId, userId);
    }

    @Transactional
    public Map<String, Object> markSameIssue(long userId, long ticketId) {
        settingService.requireEnabled();
        requireVisibleTicket(ticketId);
        // 已关闭工单仍允许标记（计划 §4.2：仍可查看、关注和标记我也遇到）。
        try {
            jdbc.update("insert into ticket_reaction (ticket_id,user_id,reaction_type) values (?,?,'same_issue')",
                    ticketId, userId);
        } catch (DuplicateKeyException ex) {
            // 幂等：重复点击不重复计数。
        }
        followTicket(ticketId, userId);
        return viewerState(ticketId, userId);
    }

    @Transactional
    public Map<String, Object> unmarkSameIssue(long userId, long ticketId) {
        settingService.requireEnabled();
        requireVisibleTicket(ticketId);
        jdbc.update("delete from ticket_reaction where ticket_id=? and user_id=? and reaction_type='same_issue'",
                ticketId, userId);
        return viewerState(ticketId, userId);
    }

    @Transactional
    public Map<String, Object> confirmResolved(long userId, long ticketId) {
        settingService.requireEnabled();
        requireAuthor(ticketId, userId);
        Map<String, Object> ticket = requireVisibleTicket(ticketId);
        if (!"resolved".equals(statusOf(ticket))) throw new BusinessException(400, "only resolved tickets can be confirmed");
        changeStatusCore(ticketId, "user", userId, "resolved", "closed", null, null, null, "resolved", true);
        return detail(ticketId, userId);
    }

    @Transactional
    public Map<String, Object> reopenByAuthor(long userId, long ticketId, Map<String, Object> req) {
        settingService.requireEnabled();
        requireAuthor(ticketId, userId);
        Map<String, Object> ticket = requireVisibleTicket(ticketId);
        String reason = requiredText(req.get("reason"), 5, 2000, "reason");
        String status = statusOf(ticket);
        boolean allowed = "resolved".equals(status)
                || ("closed".equals(status) && USER_REOPENABLE_REASONS.contains(closeReasonOf(ticket)));
        if (!allowed) throw new BusinessException(400, "this ticket state cannot be reopened by the author");
        changeStatusCore(ticketId, "user", userId, status, "in_progress", reason, null, null, null, true);
        return detail(ticketId, userId);
    }

    @Transactional
    public Map<String, Object> withdraw(long userId, long ticketId, Map<String, Object> req) {
        settingService.requireEnabled();
        requireAuthor(ticketId, userId);
        Map<String, Object> ticket = requireVisibleTicket(ticketId);
        if ("closed".equals(statusOf(ticket))) throw new BusinessException(400, "ticket is already closed");
        String note = optionalText(req == null ? null : req.get("note"), 2000, "note");
        changeStatusCore(ticketId, "user", userId, statusOf(ticket), "closed", note, null, null, "user_withdrawn", true);
        return detail(ticketId, userId);
    }

    // ==================== 管理端 ====================

    public Map<String, Object> adminList(Map<String, Object> params) {
        String keyword = text(params.get("keyword"), "");
        String status = text(params.get("status"), "");
        String category = text(params.get("category"), "");
        String module = text(params.get("module"), "");
        String priority = text(params.get("priority"), "");
        String author = text(params.get("author"), "");
        String assignee = text(params.get("assignee"), "");
        String dateFrom = text(params.get("dateFrom"), "");
        String dateTo = text(params.get("dateTo"), "");
        int page = Math.max(intValue(params.get("page"), 1), 1);
        int size = Math.min(Math.max(intValue(params.get("size"), 20), 1), 100);

        StringBuilder where = new StringBuilder(" 1=1 ");
        List<Object> args = new ArrayList<>();
        if (!keyword.isBlank()) {
            where.append(" and (t.title like ? escape '\\' or t.ticket_no like ? escape '\\') ");
            String like = "%" + escapeLike(keyword.trim()) + "%";
            args.add(like);
            args.add(like);
        }
        if (!status.isBlank()) {
            requireIn(status, STATUSES, "status");
            where.append(" and t.status=? ");
            args.add(status);
        }
        if (!category.isBlank()) {
            requireIn(category, CATEGORIES, "category");
            where.append(" and t.category=? ");
            args.add(category);
        }
        if (!module.isBlank()) {
            requireIn(module, MODULES, "module");
            where.append(" and t.module=? ");
            args.add(module);
        }
        if (!priority.isBlank()) {
            requireIn(priority, PRIORITIES, "priority");
            where.append(" and t.priority=? ");
            args.add(priority);
        }
        if (!author.isBlank()) {
            where.append(" and exists (select 1 from `user` u where u.id=t.author_id and (u.nickname like ? escape '\\' or u.phone like ? escape '\\' or u.email like ? escape '\\')) ");
            String like = "%" + escapeLike(author.trim()) + "%";
            args.add(like);
            args.add(like);
            args.add(like);
        }
        if (!assignee.isBlank()) {
            if ("unassigned".equals(assignee)) {
                where.append(" and t.assignee_admin_id is null ");
            } else {
                where.append(" and t.assignee_admin_id=? ");
                args.add(Long.parseLong(assignee));
            }
        }
        if (!dateFrom.isBlank()) {
            where.append(" and t.created_at >= ? ");
            args.add(dateFrom + " 00:00:00");
        }
        if (!dateTo.isBlank()) {
            where.append(" and t.created_at <= ? ");
            args.add(dateTo + " 23:59:59");
        }

        Integer total = jdbc.queryForObject("select count(*) from ticket t where " + where, Integer.class, args.toArray());
        List<Object> listArgs = new ArrayList<>(args);
        listArgs.add(size);
        listArgs.add((page - 1) * size);
        List<Map<String, Object>> rows = jdbc.queryForList(
                "select t.id,t.ticket_no,t.title,t.category,t.module,t.status,t.priority,t.author_id,t.assignee_admin_id,"
                        + "t.pinned,t.hidden_at,t.close_reason,t.duplicate_of_id,t.version,t.created_at,t.updated_at,t.last_activity_at,"
                        + "(select count(*) from ticket_message m where m.ticket_id=t.id and m.visibility='public' and m.hidden_at is null) as reply_count, "
                        + "(select count(*) from ticket_reaction r where r.ticket_id=t.id and r.reaction_type='same_issue') as reaction_count "
                        + "from ticket t where " + where
                        + " order by t.pinned desc, t.last_activity_at desc, t.id desc limit ? offset ?", listArgs.toArray());
        List<Map<String, Object>> items = new ArrayList<>();
        for (Map<String, Object> row : rows) items.add(adminListItemView(row));

        Map<String, Object> counts = jdbc.queryForMap(
                "select sum(case when status='open' then 1 else 0 end) openCount,"
                        + "sum(case when status='in_progress' then 1 else 0 end) inProgressCount,"
                        + "sum(case when status='waiting_reporter' then 1 else 0 end) waitingCount from ticket");
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("items", items);
        result.put("total", total == null ? 0 : total);
        result.put("page", page);
        result.put("size", size);
        result.put("counts", counts);
        return result;
    }

    public Map<String, Object> adminDetail(long ticketId) {
        Map<String, Object> row = requireTicket(ticketId);
        Map<String, Object> view = publicView(row, 0);
        view.put("priority", row.get("priority"));
        view.put("assigneeAdminId", row.get("assignee_admin_id"));
        view.put("assigneeName", row.get("assignee_admin_id") == null ? ""
                : adminDisplayName(((Number) row.get("assignee_admin_id")).longValue()));
        view.put("hiddenAt", iso(row.get("hidden_at")));
        view.put("version", row.get("version"));
        view.put("environment", rawEnvironment(row));
        return view;
    }

    public Map<String, Object> adminMessages(long ticketId, int page, int size) {
        requireTicket(ticketId);
        page = Math.max(page, 1);
        size = Math.min(Math.max(size, 1), 50);
        List<Map<String, Object>> rows = jdbc.queryForList(
                "select id,actor_type,actor_id,visibility,content,hidden_at,created_at from ticket_message "
                        + "where ticket_id=? order by created_at desc, id desc limit ? offset ?", ticketId, size, (long) (page - 1) * size);
        List<Map<String, Object>> items = new ArrayList<>();
        for (Map<String, Object> row : rows) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("id", row.get("id"));
            item.put("actorType", row.get("actor_type"));
            item.put("actorId", row.get("actor_id"));
            item.put("actorName", actorDisplayName(String.valueOf(row.get("actor_type")),
                    ((Number) row.get("actor_id")).longValue()));
            item.put("isAdmin", "admin".equals(row.get("actor_type")));
            item.put("visibility", row.get("visibility"));
            item.put("content", row.get("content"));
            item.put("hiddenAt", iso(row.get("hidden_at")));
            item.put("createdAt", iso(row.get("created_at")));
            if ("public".equals(row.get("visibility")) && row.get("hidden_at") == null) {
                item.put("attachments", boundAttachmentViews(ticketId, ((Number) row.get("id")).longValue()));
            }
            items.add(item);
        }
        return Map.of("items", items, "page", page, "size", size);
    }

    public Map<String, Object> adminEvents(long ticketId) {
        requireTicket(ticketId);
        List<Map<String, Object>> rows = jdbc.queryForList(
                "select action,from_status,to_status,note,visibility,actor_type,actor_id,created_at from ticket_event "
                        + "where ticket_id=? order by created_at asc, id asc", ticketId);
        List<Map<String, Object>> items = new ArrayList<>();
        for (Map<String, Object> row : rows) {
            Map<String, Object> item = new LinkedHashMap<>(row);
            item.put("actorName", actorDisplayName(String.valueOf(row.get("actor_type")),
                    row.get("actor_id") == null ? 0 : ((Number) row.get("actor_id")).longValue()));
            item.put("createdAt", iso(row.get("created_at")));
            items.add(item);
        }
        return Map.of("items", items);
    }

    @Transactional
    public Map<String, Object> adminReply(long adminId, long ticketId, Map<String, Object> req) {
        requireTicket(ticketId);
        boolean internal = Boolean.TRUE.equals(req.get("internal"));
        String content = requiredText(req.get("content"), 1, 3000, "content");
        List<Number> attachmentIds = attachmentIds(req.get("attachmentIds"));
        jdbc.update("insert into ticket_message (ticket_id,actor_type,actor_id,visibility,content) values (?,'admin',?,?,?)",
                ticketId, adminId, internal ? "internal" : "public", content);
        Long messageId = jdbc.queryForObject(
                "select id from ticket_message where ticket_id=? and actor_type='admin' and actor_id=? order by id desc limit 1",
                Long.class, ticketId, adminId);
        storage.bindAll(attachmentIds, "admin", adminId, ticketId, messageId, "reply", MAX_IMAGES_PER_CONTENT);
        if (!internal) {
            jdbc.update("update ticket set last_activity_at=utc_timestamp() where id=?", ticketId);
            insertEvent(ticketId, "admin", adminId, "admin_replied", null, null, null, "public");
            notifyParticipants(ticketId, adminId, "工单有管理员回复",
                    "你的工单 " + ticketNo(ticketId) + " 有新的管理员回复");
        } else {
            insertEvent(ticketId, "admin", adminId, "internal_note", null, null, null, "internal");
        }
        return adminMessages(ticketId, 1, 20);
    }

    @Transactional
    public Map<String, Object> changeStatus(long adminId, long ticketId, Map<String, Object> req) {
        Map<String, Object> ticket = requireTicket(ticketId);
        String from = statusOf(ticket);
        String target = requiredEnum(req.get("status"), STATUSES, "status");
        long version = longValue(req.get("version"), -1);
        if (version < 0) throw new BusinessException(400, "version is required for admin updates");

        String resolution = optionalText(req.get("resolution"), 2000, "resolution");
        String fixedVersion = optionalText(req.get("fixedVersion"), 50, "fixedVersion");
        String closeReason = text(req.get("closeReason"), "");
        String note = text(req.get("note"), "");

        if (!"closed".equals(target) && !closeReason.isBlank()) {
            throw new BusinessException(400, "closeReason is only allowed when closing");
        }
        if ("resolved".equals(target)) {
            if (resolution == null || resolution.length() < 10) throw new BusinessException(400, "resolution of at least 10 characters is required");
        }
        if ("closed".equals(target)) {
            requireIn(closeReason, Set.of("resolved", "duplicate", "user_withdrawn", "insufficient_info",
                    "not_supported", "violation", "other"), "closeReason");
            if ("duplicate".equals(closeReason)) {
                throw new BusinessException(400, "duplicate tickets must be merged, not closed directly");
            }
            if (("insufficient_info".equals(closeReason) || "other".equals(closeReason)) && note.length() < 5) {
                throw new BusinessException(400, "a close note of at least 5 characters is required");
            }
        }
        if ("waiting_reporter".equals(target) && note.length() < 5) {
            throw new BusinessException(400, "state what information is needed (at least 5 characters)");
        }
        if ("in_progress".equals(from) || "open".equals(from) || "waiting_reporter".equals(from)) {
            if (!Set.of("in_progress", "waiting_reporter", "resolved", "closed").contains(target) || target.equals(from)) {
                throw new BusinessException(400, "invalid status transition " + from + " -> " + target);
            }
        } else if ("resolved".equals(from)) {
            if (!Set.of("closed", "in_progress").contains(target)) {
                throw new BusinessException(400, "invalid status transition " + from + " -> " + target);
            }
            if ("closed".equals(target) && !"resolved".equals(closeReason)) {
                closeReason = "resolved";
            }
        } else if ("closed".equals(from)) {
            if (!"in_progress".equals(target)) throw new BusinessException(400, "closed tickets can only be reopened");
            if ("duplicate".equals(closeReasonOf(ticket))) {
                throw new BusinessException(400, "merged tickets must be restored through unmerge");
            }
            if (note.length() < 5) throw new BusinessException(400, "a reopen reason of at least 5 characters is required");
        }

        changeStatusCore(ticketId, "admin", adminId, from, target, note.isBlank() ? null : note,
                resolution, fixedVersion,
                target.equals("closed") ? closeReason : null, true, version);
        return adminDetail(ticketId);
    }

    @Transactional
    public Map<String, Object> assign(long adminId, long ticketId, Map<String, Object> req) {
        requireTicket(ticketId);
        Long assignee = req.get("assigneeAdminId") == null ? null : longValue(req.get("assigneeAdminId"), -1);
        if (assignee != null) {
            Map<String, Object> admin = requireAdminUser(assignee);
            if (!"active".equals(String.valueOf(admin.get("status")))) {
                throw new BusinessException(400, "assignee must be an active administrator");
            }
        }
        int updated = jdbc.update("update ticket set assignee_admin_id=?,version=version+1,updated_at=utc_timestamp() where id=? and version=?",
                assignee, ticketId, longValue(req.get("version"), -1));
        requireVersionMatch(updated);
        insertEvent(ticketId, "admin", adminId, assignee == null ? "unassigned" : "assigned",
                null, null, assignee == null ? "处理人已清空" : "处理人已设置", "public");
        return adminDetail(ticketId);
    }

    @Transactional
    public Map<String, Object> setPriority(long adminId, long ticketId, Map<String, Object> req) {
        requireTicket(ticketId);
        String priority = requiredEnum(req.get("priority"), PRIORITIES, "priority");
        int updated = jdbc.update("update ticket set priority=?,version=version+1,updated_at=utc_timestamp() where id=? and version=?",
                priority, ticketId, longValue(req.get("version"), -1));
        requireVersionMatch(updated);
        insertEvent(ticketId, "admin", adminId, "priority_changed", null, null, "优先级调整为 " + priority, "public");
        return adminDetail(ticketId);
    }

    @Transactional
    public Map<String, Object> setPinned(long adminId, long ticketId, Map<String, Object> req) {
        requireTicket(ticketId);
        boolean pinned = Boolean.TRUE.equals(req.get("pinned"));
        int updated = jdbc.update("update ticket set pinned=?,version=version+1,updated_at=utc_timestamp() where id=? and version=?",
                pinned, ticketId, longValue(req.get("version"), -1));
        requireVersionMatch(updated);
        insertEvent(ticketId, "admin", adminId, pinned ? "pinned" : "unpinned", null, null,
                pinned ? "工单已置顶" : "已取消置顶", "public");
        return adminDetail(ticketId);
    }

    @Transactional
    public Map<String, Object> setHidden(long adminId, long ticketId, Map<String, Object> req, boolean hidden) {
        requireTicket(ticketId);
        String reason = optionalText(req.get("reason"), 500, "reason");
        if (hidden && reason.length() < 3) throw new BusinessException(400, "a hide reason is required");
        long version = longValue(req.get("version"), -1);
        int updated;
        if (hidden) {
            updated = jdbc.update("update ticket set hidden_at=utc_timestamp(),hidden_by=?,version=version+1,updated_at=utc_timestamp() "
                    + "where id=? and version=? and hidden_at is null", adminId, ticketId, version);
        } else {
            updated = jdbc.update("update ticket set hidden_at=null,hidden_by=null,version=version+1,updated_at=utc_timestamp() "
                    + "where id=? and version=? and hidden_at is not null", ticketId, version);
        }
        if (updated == 0) throw new BusinessException(409, "ticket state changed, please refresh");
        insertEvent(ticketId, "admin", adminId, hidden ? "hidden" : "restored", null, null,
                hidden ? reason : "已恢复可见", "internal");
        return adminDetail(ticketId);
    }

    @Transactional
    public Map<String, Object> merge(long adminId, long targetId, Map<String, Object> req) {
        long sourceId = longValue(req.get("sourceId"), -1);
        String note = requiredText(req.get("note"), 5, 1000, "note");
        if (sourceId == targetId) throw new BusinessException(400, "cannot merge a ticket into itself");
        Map<String, Object> target = requireTicket(targetId);
        Map<String, Object> source = requireTicket(sourceId);
        if (hiddenOf(target)) throw new BusinessException(400, "merge target must be visible");
        if ("closed".equals(statusOf(target))) throw new BusinessException(400, "merge target must not be closed");
        if (duplicateOf(target) != null) throw new BusinessException(400, "merge target is already a duplicate");
        if (duplicateOf(source) != null) throw new BusinessException(400, "source ticket is already merged");
        Integer existingDuplicates = jdbc.queryForObject(
                "select count(*) from ticket where duplicate_of_id=?", Integer.class, targetId);
        if (existingDuplicates != null && existingDuplicates > 0) {
            throw new BusinessException(400, "this ticket already received duplicates; merge into that ticket instead");
        }

        jdbc.update("update ticket set status='closed',close_reason='duplicate',close_note=?,duplicate_of_id=?,merged_at=utc_timestamp(),"
                        + "version=version+1,updated_at=utc_timestamp() where id=? and duplicate_of_id is null",
                note, targetId, sourceId);
        // 源工单作者与关注者以去重方式关注主工单；源关注记录保留。
        List<Long> sourceFollowers = jdbc.queryForList("select user_id from ticket_follow where ticket_id=?", Long.class, sourceId);
        LinkedHashSet<Long> toFollow = new LinkedHashSet<>(sourceFollowers);
        toFollow.add(((Number) source.get("author_id")).longValue());
        for (Long follower : toFollow) followTicket(targetId, follower);

        insertEvent(sourceId, "admin", adminId, "merged", statusOf(source), "closed", "已合并至 " + ticketNo(targetId), "public");
        insertEvent(targetId, "admin", adminId, "received_merge", null, null, "工单 " + ticketNo(sourceId) + " 已合并入本工单", "public");
        notifyParticipants(sourceId, adminId, "工单已合并",
                "工单 " + ticketNo(sourceId) + " 已合并至 " + ticketNo(targetId));
        return adminDetail(sourceId);
    }

    @Transactional
    public Map<String, Object> unmerge(long adminId, long sourceId, Map<String, Object> req) {
        Map<String, Object> source = requireTicket(sourceId);
        Long targetId = duplicateOf(source);
        if (targetId == null) throw new BusinessException(400, "this ticket is not merged");
        String note = requiredText(req.get("note"), 5, 1000, "note");
        jdbc.update("update ticket set status='open',close_reason=null,close_note=null,duplicate_of_id=null,merged_at=null,"
                        + "version=version+1,updated_at=utc_timestamp(),last_activity_at=utc_timestamp() where id=? and duplicate_of_id is not null",
                sourceId);
        insertEvent(sourceId, "admin", adminId, "unmerged", "closed", "open", note, "public");
        insertEvent(targetId, "admin", adminId, "merge_cancelled", null, null,
                "工单 " + ticketNo(sourceId) + " 已取消合并", "public");
        notifyParticipants(sourceId, adminId, "工单恢复处理",
                "工单 " + ticketNo(sourceId) + " 已取消合并，重新进入待处理");
        return adminDetail(sourceId);
    }

    // ==================== 状态机核心 ====================

    private void changeStatusCore(long ticketId, String actorType, long actorId, String from, String to,
                                  String note, String resolution, String fixedVersion, String closeReason,
                                  boolean notify) {
        String sql = "update ticket set status=?,resolution=coalesce(?,resolution),fixed_version=coalesce(?,fixed_version),"
                + "close_reason=?,close_note=case when ? is null then close_note else ? end,"
                + "version=version+1,updated_at=utc_timestamp(),last_activity_at=utc_timestamp() where id=? and status=?";
        jdbc.update(sql, to, resolution, fixedVersion, closeReason, note, note, ticketId, from);
        insertEvent(ticketId, actorType, actorId, "status_changed", from, to, note, "public");
        if (notify) {
            notifyParticipants(ticketId, actorId, "工单状态更新",
                    "工单 " + ticketNo(ticketId) + " 状态更新为 " + statusLabel(to));
        }
    }

    private void changeStatusCore(long ticketId, String actorType, long actorId, String from, String to,
                                  String note, String resolution, String fixedVersion, String closeReason,
                                  boolean notify, long expectedVersion) {
        String sql = "update ticket set status=?,resolution=coalesce(?,resolution),fixed_version=coalesce(?,fixed_version),"
                + "close_reason=?,close_note=case when ? is null then close_note else ? end,"
                + "version=version+1,updated_at=utc_timestamp(),last_activity_at=utc_timestamp() where id=? and status=? and version=?";
        int updated = jdbc.update(sql, to, resolution, fixedVersion, closeReason, note, note, ticketId, from, expectedVersion);
        if (updated == 0) throw new BusinessException(409, "ticket state changed, please refresh");
        insertEvent(ticketId, actorType, actorId, "status_changed", from, to, note, "public");
        if (notify) {
            notifyParticipants(ticketId, actorId, "工单状态更新",
                    "工单 " + ticketNo(ticketId) + " 状态更新为 " + statusLabel(to));
        }
    }

    // ==================== 视图组装 ====================

    private Map<String, Object> publicView(Map<String, Object> row, long userId) {
        long ticketId = ((Number) row.get("id")).longValue();
        Map<String, Object> counts = jdbc.queryForMap(
                "select (select count(*) from ticket_message m where m.ticket_id=? and m.visibility='public' and m.hidden_at is null) replyCount,"
                        + "(select count(*) from ticket_reaction r where r.ticket_id=? and r.reaction_type='same_issue') reactionCount,"
                        + "(select count(*) from ticket_follow f where f.ticket_id=?) followerCount", ticketId, ticketId, ticketId);
        Map<String, Object> view = new LinkedHashMap<>();
        view.put("id", ticketId);
        view.put("ticketNo", row.get("ticket_no"));
        view.put("title", row.get("title"));
        view.put("category", row.get("category"));
        view.put("module", row.get("module"));
        view.put("description", row.get("description"));
        view.put("steps", row.get("steps"));
        view.put("expectedResult", row.get("expected_result"));
        view.put("environment", rawEnvironment(row));
        view.put("pagePath", row.get("page_path"));
        view.put("status", row.get("status"));
        view.put("resolution", row.get("resolution"));
        view.put("fixedVersion", row.get("fixed_version"));
        view.put("closeReason", row.get("close_reason"));
        view.put("closeNote", row.get("close_note"));
        view.put("pinned", row.get("pinned"));
        view.put("authorName", userDisplayName(((Number) row.get("author_id")).longValue()));
        view.put("mergedAt", iso(row.get("merged_at")));
        Long duplicateOf = duplicateOf(row);
        view.put("duplicateOfId", duplicateOf);
        if (duplicateOf != null) {
            List<Map<String, Object>> target = jdbc.queryForList(
                    "select id,ticket_no,title from ticket where id=?", duplicateOf);
            view.put("duplicateOf", target.isEmpty() ? null : Map.of(
                    "id", target.get(0).get("id"),
                    "ticketNo", target.get(0).get("ticket_no"),
                    "title", target.get(0).get("title")));
        } else {
            view.put("duplicateOf", null);
        }
        view.put("replyCount", counts.get("replyCount"));
        view.put("reactionCount", counts.get("reactionCount"));
        view.put("followerCount", counts.get("followerCount"));
        view.put("createdAt", iso(row.get("created_at")));
        view.put("updatedAt", iso(row.get("updated_at")));
        view.put("lastActivityAt", iso(row.get("last_activity_at")));
        view.put("attachments", boundAttachmentViews(ticketId, null));
        if (userId > 0) view.put("viewer", viewerState(ticketId, userId));
        return view;
    }

    private Map<String, Object> listItemView(Map<String, Object> row, long userId) {
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("id", row.get("id"));
        item.put("ticketNo", row.get("ticket_no"));
        item.put("title", row.get("title"));
        item.put("category", row.get("category"));
        item.put("module", row.get("module"));
        item.put("status", row.get("status"));
        item.put("pinned", row.get("pinned"));
        item.put("closeReason", row.get("close_reason"));
        item.put("duplicateOfId", duplicateOf(row));
        item.put("authorName", userDisplayName(((Number) row.get("author_id")).longValue()));
        item.put("replyCount", row.get("reply_count"));
        item.put("reactionCount", row.get("reaction_count"));
        item.put("createdAt", iso(row.get("created_at")));
        item.put("lastActivityAt", iso(row.get("last_activity_at")));
        item.put("viewer", userId > 0 ? viewerState(((Number) row.get("id")).longValue(), userId) : null);
        return item;
    }

    private Map<String, Object> adminListItemView(Map<String, Object> row) {
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("id", row.get("id"));
        item.put("ticketNo", row.get("ticket_no"));
        item.put("title", row.get("title"));
        item.put("category", row.get("category"));
        item.put("module", row.get("module"));
        item.put("status", row.get("status"));
        item.put("priority", row.get("priority"));
        item.put("pinned", row.get("pinned"));
        item.put("hidden", row.get("hidden_at") != null);
        item.put("closeReason", row.get("close_reason"));
        item.put("duplicateOfId", duplicateOf(row));
        item.put("authorName", userDisplayName(((Number) row.get("author_id")).longValue()));
        item.put("assigneeName", row.get("assignee_admin_id") == null ? ""
                : adminDisplayName(((Number) row.get("assignee_admin_id")).longValue()));
        item.put("version", row.get("version"));
        item.put("replyCount", row.get("reply_count"));
        item.put("reactionCount", row.get("reaction_count"));
        item.put("createdAt", iso(row.get("created_at")));
        item.put("lastActivityAt", iso(row.get("last_activity_at")));
        return item;
    }

    private List<Map<String, Object>> boundAttachmentViews(long ticketId, Long messageId) {
        List<Map<String, Object>> rows = messageId == null
                ? jdbc.queryForList("select id,mime_type,width,height,original_name from ticket_attachment where ticket_id=? and message_id is null and state='bound'", ticketId)
                : jdbc.queryForList("select id,mime_type,width,height,original_name from ticket_attachment where message_id=? and state='bound'", messageId);
        List<Map<String, Object>> items = new ArrayList<>();
        for (Map<String, Object> row : rows) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("id", row.get("id"));
            item.put("mimeType", row.get("mime_type"));
            item.put("width", row.get("width"));
            item.put("height", row.get("height"));
            item.put("name", row.get("original_name"));
            items.add(item);
        }
        return items;
    }

    // ==================== 通知 ====================

    /** 通知作者与全部关注者（去重、排除操作者本人）；单条失败只记日志，不影响主流程。 */
    private void notifyParticipants(long ticketId, long actorId, String title, String content) {
        Map<String, Object> ticket = requireTicket(ticketId);
        if (hiddenOf(ticket)) return;
        LinkedHashSet<Long> recipients = new LinkedHashSet<>();
        recipients.add(((Number) ticket.get("author_id")).longValue());
        recipients.addAll(jdbc.queryForList("select user_id from ticket_follow where ticket_id=?", Long.class, ticketId));
        String route = "/tickets/" + ticketId;
        for (Long recipient : recipients) {
            if (recipient == actorId) continue;
            try {
                notificationService.createNotification(recipient, "ticket", title, content, "ticket", ticketId, null, null, route, 0);
            } catch (RuntimeException ex) {
                log.warn("failed to deliver ticket notification to user {}", recipient, ex);
            }
        }
    }

    // ==================== 内部辅助 ====================

    private Map<String, Object> requireTicket(long ticketId) {
        List<Map<String, Object>> rows = jdbc.queryForList("select * from ticket where id=?", ticketId);
        if (rows.isEmpty()) throw new BusinessException(404, "ticket not found");
        return rows.get(0);
    }

    private Map<String, Object> requireVisibleTicket(long ticketId) {
        Map<String, Object> row = requireTicket(ticketId);
        if (row.get("hidden_at") != null) throw new BusinessException(404, "ticket not found");
        return row;
    }

    /** 工单是否被管理员隐藏（用于附件读取等轻量判断）。 */
    public boolean isHidden(long ticketId) {
        Map<String, Object> row = requireTicket(ticketId);
        return hiddenOf(row);
    }

    private void requireAuthor(long ticketId, long userId) {
        if (!isAuthor(ticketId, userId)) throw new BusinessException(403, "only the ticket author can do this");
    }

    private boolean isAuthor(long ticketId, long userId) {
        Integer count = jdbc.queryForObject("select count(*) from ticket where id=? and author_id=?", Integer.class, ticketId, userId);
        return count != null && count > 0;
    }

    private void followTicket(long ticketId, long userId) {
        try {
            jdbc.update("insert into ticket_follow (ticket_id,user_id) values (?,?)", ticketId, userId);
        } catch (DuplicateKeyException ex) {
            // 幂等。
        }
    }

    private void insertEvent(long ticketId, String actorType, Long actorId, String action,
                             String fromStatus, String toStatus, String note, String visibility) {
        jdbc.update("insert into ticket_event (ticket_id,actor_type,actor_id,action,from_status,to_status,note,visibility) values (?,?,?,?,?,?,?,?)",
                ticketId, actorType, actorId, action, fromStatus, toStatus, note, visibility);
    }

    private String actorDisplayName(String actorType, long actorId) {
        return "admin".equals(actorType) ? adminDisplayName(actorId) : userDisplayName(actorId);
    }

    private String userDisplayName(long userId) {
        List<Map<String, Object>> rows = jdbc.queryForList("select nickname,status,deleted_at from `user` where id=?", userId);
        if (rows.isEmpty()) return "已注销用户";
        Map<String, Object> row = rows.get(0);
        if (row.get("deleted_at") != null || !"active".equals(String.valueOf(row.get("status")))) return "已注销用户";
        String nickname = String.valueOf(row.get("nickname"));
        return nickname.isBlank() ? "用户" + userId : nickname;
    }

    private String adminDisplayName(long adminId) {
        List<String> names = jdbc.query("select username from admin_user where id=?", (rs, i) -> rs.getString(1), adminId);
        return names.isEmpty() ? "管理员" : "管理员 " + names.get(0);
    }

    private Map<String, Object> requireAdminUser(long adminId) {
        List<Map<String, Object>> rows = jdbc.queryForList("select id,username,role,status from admin_user where id=?", adminId);
        if (rows.isEmpty()) throw new BusinessException(400, "assignee must be a valid administrator");
        return rows.get(0);
    }

    private void requireVersionMatch(int updated) {
        if (updated == 0) throw new BusinessException(409, "ticket state changed, please refresh");
    }

    private Map<String, Object> idempotencyLookup(long userId, String action, String key, String hash) {
        if (key.isBlank()) return null;
        List<Map<String, Object>> rows = jdbc.queryForList(
                "select request_hash,result_json from ticket_idempotency where user_id=? and action=? and idempotency_key=? and created_at >= ?",
                userId, action, key, Timestamp.from(Instant.now().minusSeconds(86_400)));
        if (rows.isEmpty()) return null;
        Map<String, Object> row = rows.get(0);
        if (!hash.equals(String.valueOf(row.get("request_hash")))) {
            throw new BusinessException(409, "idempotency key reused with a different payload");
        }
        return jsonToMap(String.valueOf(row.get("result_json")));
    }

    private void idempotencySave(long userId, String action, String key, String hash, long ticketId) {
        try {
            jdbc.update("insert into ticket_idempotency (user_id,action,idempotency_key,request_hash,result_json) values (?,?,?,?,?)",
                    userId, action, key, hash, "{\"id\":" + ticketId + "}");
        } catch (DuplicateKeyException ex) {
            // 并发重复提交：以先到的请求结果为准。
        }
    }

    private String statusOf(Map<String, Object> ticket) {
        return String.valueOf(ticket.get("status"));
    }

    private String closeReasonOf(Map<String, Object> ticket) {
        Object value = ticket.get("close_reason");
        return value == null ? "" : String.valueOf(value);
    }

    private boolean hiddenOf(Map<String, Object> ticket) {
        return ticket.get("hidden_at") != null;
    }

    private Long duplicateOf(Map<String, Object> ticket) {
        Object value = ticket.get("duplicate_of_id");
        return value instanceof Number number ? number.longValue() : null;
    }

    private String ticketNo(Long ticketId) {
        return "TK-" + String.format("%08d", ticketId);
    }

    private String statusLabel(String status) {
        return switch (status) {
            case "open" -> "待处理";
            case "in_progress" -> "处理中";
            case "waiting_reporter" -> "待补充";
            case "resolved" -> "已解决";
            case "closed" -> "已关闭";
            default -> status;
        };
    }

    private Map<String, Object> rawEnvironment(Map<String, Object> row) {
        Object raw = row.get("environment_json");
        if (raw == null) return Map.of();
        Map<String, Object> parsed = jsonToMap(String.valueOf(raw));
        return parsed == null ? Map.of() : parsed;
    }

    private Map<String, Object> environmentOf(Object raw) {
        if (!(raw instanceof Map<?, ?> map)) return Map.of();
        Map<String, Object> environment = new LinkedHashMap<>();
        for (Map.Entry<?, ?> entry : map.entrySet()) {
            if (entry.getKey() == null || entry.getValue() == null) continue;
            String key = String.valueOf(entry.getKey());
            String value = String.valueOf(entry.getValue());
            if (key.length() > 50 || value.length() > 200) continue;
            environment.put(key, value);
        }
        return environment;
    }

    private String sanitizePagePath(String raw) {
        String path = raw.strip();
        if (path.isEmpty()) return "";
        int query = path.indexOf('?');
        if (query >= 0) path = path.substring(0, query);
        int fragment = path.indexOf('#');
        if (fragment >= 0) path = path.substring(0, fragment);
        if (!path.startsWith("/") || path.contains("..") || path.length() > 500) return "";
        return path;
    }

    private List<Number> attachmentIds(Object raw) {
        List<Number> ids = new ArrayList<>();
        if (!(raw instanceof List<?> list)) return ids;
        for (Object item : list) {
            if (item instanceof Number number) ids.add(number);
            else if (item != null) ids.add(Long.parseLong(String.valueOf(item)));
        }
        return ids;
    }

    private String requiredText(Object raw, int min, int max, String field) {
        String value = text(raw, "").strip();
        if (value.length() < min || value.length() > max) {
            throw new BusinessException(400, field + " must be between " + min + " and " + max + " characters");
        }
        return value;
    }

    private String optionalText(Object raw, int max, String field) {
        String value = text(raw, "").strip();
        if (value.length() > max) throw new BusinessException(400, field + " is limited to " + max + " characters");
        return value.isEmpty() ? null : value;
    }

    private String requiredEnum(Object raw, Set<String> allowed, String field) {
        String value = text(raw, "");
        requireIn(value, allowed, field);
        return value;
    }

    private void requireIn(String value, Set<String> allowed, String field) {
        if (!allowed.contains(value)) throw new BusinessException(400, "invalid " + field);
    }

    private String text(Object raw, String fallback) {
        return raw == null ? fallback : String.valueOf(raw);
    }

    private int intValue(Object raw, int fallback) {
        if (raw instanceof Number number) return number.intValue();
        try {
            return raw == null ? fallback : Integer.parseInt(String.valueOf(raw));
        } catch (NumberFormatException ex) {
            return fallback;
        }
    }

    private long longValue(Object raw, long fallback) {
        if (raw instanceof Number number) return number.longValue();
        try {
            return raw == null ? fallback : Long.parseLong(String.valueOf(raw));
        } catch (NumberFormatException ex) {
            return fallback;
        }
    }

    private String escapeLike(String value) {
        return value.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
    }

    /** MySQL 驱动在 UTC 会话下返回 LocalDateTime，这里统一转成 ISO-8601 UTC 字符串。 */
    private String iso(Object value) {
        if (value == null) return null;
        if (value instanceof java.sql.Timestamp ts) return ts.toInstant().toString();
        if (value instanceof java.time.LocalDateTime dt) return dt.toInstant(java.time.ZoneOffset.UTC).toString();
        if (value instanceof java.time.OffsetDateTime odt) return odt.toInstant().toString();
        if (value instanceof java.time.Instant instant) return instant.toString();
        return null;
    }

    private String toJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (com.fasterxml.jackson.core.JsonProcessingException ex) {
            throw new IllegalStateException("failed to serialize ticket json", ex);
        }
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> jsonToMap(String json) {
        if (json == null || json.isBlank() || "null".equals(json)) return null;
        try {
            return objectMapper.readValue(json, Map.class);
        } catch (java.io.IOException ex) {
            return null;
        }
    }

    private String requestHash(Object... parts) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            for (Object part : parts) {
                digest.update((String.valueOf(part) + "\u0001").getBytes(StandardCharsets.UTF_8));
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException(ex);
        }
    }
}
