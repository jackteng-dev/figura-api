package dev.jackteng.figura.service;

import dev.jackteng.figura.mapper.FulfillmentMapper;
import dev.jackteng.figura.pojo.CustomUserDetails;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.*;

/**
 * Commerce V2 Phase 6：客服單。顧客只看自己的客服單與非內部訊息；關聯訂單必須是本人的。
 * 冪等：建立以（customer, requestId）、回覆以（ticket, requestId）判斷；同 requestId 不同內容 409。
 * 狀態：顧客發言 → WAITING_STAFF；員工對顧客回覆 → WAITING_CUSTOMER；員工可設 RESOLVED／CLOSED；顧客在 CLOSED 後不能再回覆。
 */
@Service
public class SupportService {
    public static final List<String> CATEGORIES = List.of("ORDER", "PREORDER", "SHIPPING", "RETURN_DEFECT", "PAYMENT", "ACCOUNT", "PRODUCT", "OTHER");
    public static final List<String> STATUSES = List.of("WAITING_STAFF", "WAITING_CUSTOMER", "RESOLVED", "CLOSED");
    private static final ZoneOffset TAIPEI = ZoneOffset.ofHours(8);
    private final JdbcTemplate db; private final FulfillmentMapper staff;
    public SupportService(JdbcTemplate db, FulfillmentMapper staff) { this.db = db; this.staff = staff; }

    public record Message(String id, String authorType, String staffUsername, boolean internal, String body, OffsetDateTime createdAt) {}
    public record Ticket(String id, String customerId, String category, String subject, String orderId, String preorderOrderId, String status, Integer assigneeId,
                         int version, OffsetDateTime createdAt, OffsetDateTime updatedAt) {}
    public record Detail(Ticket ticket, List<Message> messages) {}
    public record Page(List<Ticket> items, int page, int pageSize, long total) {}
    public record Applied(Detail current, boolean replayed) {}

    private static ResponseStatusException fail(int s, String m) { return new ResponseStatusException(HttpStatus.valueOf(s), m); }
    private static OffsetDateTime time(Object v) { return v == null ? null : (v instanceof java.sql.Timestamp t ? t.toLocalDateTime() : (LocalDateTime) v).atOffset(TAIPEI); }
    private static Ticket ticket(Map<String, Object> r) {
        return new Ticket((String) r.get("id"), (String) r.get("customer_id"), (String) r.get("category"), (String) r.get("subject"), (String) r.get("order_id"),
                (String) r.get("preorder_order_id"), (String) r.get("status"), r.get("assignee_id") == null ? null : ((Number) r.get("assignee_id")).intValue(),
                ((Number) r.get("version")).intValue(), time(r.get("created_at")), time(r.get("updated_at")));
    }
    private Detail detail(String id, boolean includeInternal) {
        var t = ticket(db.queryForMap("SELECT * FROM support_ticket WHERE id=?", id));
        var msgs = db.query("SELECT * FROM support_message WHERE ticket_id=?" + (includeInternal ? "" : " AND internal=FALSE") + " ORDER BY created_at,id",
                (r, n) -> new Message(r.getString("id"), r.getString("author_type"), r.getString("staff_username"), r.getBoolean("internal"), r.getString("body"),
                        time(r.getObject("created_at", LocalDateTime.class))), id);
        return new Detail(t, msgs);
    }
    private static void page(int page, int size) { if (page < 1 || page > 10000 || size < 1 || size > 50) throw fail(400, "分頁範圍不正確"); }
    private Map<String, Object> own(String customer, String id, boolean lock) {
        if (id == null || !id.matches("[0-9a-f-]{36}")) throw fail(404, "找不到客服單");
        var rows = db.queryForList("SELECT * FROM support_ticket WHERE id=? AND customer_id=?" + (lock ? " FOR UPDATE" : ""), id, customer);
        if (rows.isEmpty()) throw fail(404, "找不到客服單"); return rows.get(0);
    }

    // ---- 顧客 ----
    @Transactional(readOnly = true)
    public Page mine(String customer, int page, int size) {
        page(page, size);
        return new Page(db.queryForList("SELECT * FROM support_ticket WHERE customer_id=? ORDER BY updated_at DESC,id DESC LIMIT ? OFFSET ?", customer, size, (page - 1) * size).stream().map(SupportService::ticket).toList(),
                page, size, db.queryForObject("SELECT COUNT(*) FROM support_ticket WHERE customer_id=?", Long.class, customer));
    }
    @Transactional(readOnly = true) public Detail mineDetail(String customer, String id) { own(customer, id, false); return detail(id, false); }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public Applied create(String customer, String request, String category, String subject, String body, String orderId, String preorderOrderId) {
        String c = category.trim().toUpperCase(Locale.ROOT), s = subject.trim(), b = body.trim();
        if (!CATEGORIES.contains(c)) throw fail(400, "問題類型不正確");
        if (s.isEmpty() || b.isEmpty()) throw fail(400, "主旨與內容不可空白");
        if (orderId != null && preorderOrderId != null) throw fail(400, "只能關聯一張訂單");
        if (db.queryForList("SELECT id FROM customer WHERE id=? AND enabled=TRUE FOR UPDATE", String.class, customer).isEmpty()) throw fail(401, "顧客登入狀態無效");
        var prior = db.queryForList("SELECT * FROM support_ticket WHERE customer_id=? AND request_id=?", customer, request);
        if (!prior.isEmpty()) {
            var t = prior.get(0);
            var first = db.queryForList("SELECT body FROM support_message WHERE ticket_id=? ORDER BY created_at,id LIMIT 1", String.class, t.get("id"));
            if (!c.equals(t.get("category")) || !s.equals(t.get("subject")) || !Objects.equals(orderId, t.get("order_id")) || !Objects.equals(preorderOrderId, t.get("preorder_order_id"))
                    || first.isEmpty() || !first.get(0).equals(b)) throw fail(409, "操作編號已用於不同內容");
            return new Applied(detail((String) t.get("id"), false), true);
        }
        // 只能關聯本人的訂單
        if (orderId != null && db.queryForObject("SELECT COUNT(*) FROM customer_order WHERE id=? AND customer_id=?", Integer.class, orderId, customer) == 0) throw fail(404, "找不到訂單");
        if (preorderOrderId != null && db.queryForObject("SELECT COUNT(*) FROM preorder_order WHERE id=? AND customer_id=?", Integer.class, preorderOrderId, customer) == 0) throw fail(404, "找不到預購訂單");
        long open = db.queryForObject("SELECT COUNT(*) FROM support_ticket WHERE customer_id=? AND status IN ('WAITING_STAFF','WAITING_CUSTOMER')", Long.class, customer);
        if (open >= 20) throw fail(409, "處理中的客服單過多，請在既有客服單中補充");
        String id = UUID.randomUUID().toString();
        db.update("INSERT INTO support_ticket(id,customer_id,request_id,category,subject,order_id,preorder_order_id) VALUES(?,?,?,?,?,?,?)", id, customer, request, c, s, orderId, preorderOrderId);
        db.update("INSERT INTO support_message(id,ticket_id,request_id,author_type,customer_id,body) VALUES(?,?,?,'CUSTOMER',?,?)", UUID.randomUUID().toString(), id, request, customer, b);
        return new Applied(detail(id, false), false);
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public Applied customerReply(String customer, String id, String request, String body) {
        String b = body.trim(); if (b.isEmpty()) throw fail(400, "內容不可空白");
        // 鎖序 customer → ticket，與員工回覆一致，避免互等
        if (db.queryForList("SELECT id FROM customer WHERE id=? FOR UPDATE", String.class, customer).isEmpty()) throw fail(401, "顧客登入狀態無效");
        var t = own(customer, id, true);
        var prior = db.queryForList("SELECT body,author_type FROM support_message WHERE ticket_id=? AND request_id=?", id, request);
        if (!prior.isEmpty()) { if (!b.equals(prior.get(0).get("body")) || !"CUSTOMER".equals(prior.get(0).get("author_type"))) throw fail(409, "操作編號已用於不同內容"); return new Applied(detail(id, false), true); }
        if ("CLOSED".equals(t.get("status"))) throw fail(409, "客服單已結案，請建立新的客服單");
        if (db.queryForObject("SELECT COUNT(*) FROM support_message WHERE ticket_id=?", Integer.class, id) >= 200) throw fail(409, "訊息過多，請建立新的客服單");
        db.update("INSERT INTO support_message(id,ticket_id,request_id,author_type,customer_id,body) VALUES(?,?,?,'CUSTOMER',?,?)", UUID.randomUUID().toString(), id, request, customer, b);
        db.update("UPDATE support_ticket SET status='WAITING_STAFF',version=version+1,updated_at=CURRENT_TIMESTAMP(6) WHERE id=?", id);
        return new Applied(detail(id, false), false);
    }

    // ---- 員工 ----
    private String authorize(CustomUserDetails actor, String code) {
        String name = actor == null ? null : staff.lockActor(actor.getId());
        if (name == null || staff.lockPermission(actor.getId(), code).isEmpty()) throw fail(403, "員工或客服授權已失效");
        return name;
    }
    @Transactional(readOnly = true)
    public Page queue(int page, int size, String status, Integer assignee) {
        page(page, size);
        if (status != null && !STATUSES.contains(status)) throw fail(400, "狀態不正確");
        String where = " FROM support_ticket WHERE (? IS NULL OR status=?) AND (? IS NULL OR assignee_id=?)";
        return new Page(db.queryForList("SELECT *" + where + " ORDER BY FIELD(status,'WAITING_STAFF','WAITING_CUSTOMER','RESOLVED','CLOSED'),updated_at,id LIMIT ? OFFSET ?", status, status, assignee, assignee, size, (page - 1) * size)
                .stream().map(SupportService::ticket).toList(), page, size, db.queryForObject("SELECT COUNT(*)" + where, Long.class, status, status, assignee, assignee));
    }
    @Transactional(readOnly = true)
    public Detail staffDetail(String id) {
        if (db.queryForObject("SELECT COUNT(*) FROM support_ticket WHERE id=?", Integer.class, id) == 0) throw fail(404, "找不到客服單");
        return detail(id, true);
    }
    private Map<String, Object> lockTicket(String id) {
        var known = db.queryForList("SELECT customer_id FROM support_ticket WHERE id=?", String.class, id);
        if (known.isEmpty()) throw fail(404, "找不到客服單");
        db.queryForList("SELECT id FROM customer WHERE id=? FOR UPDATE", String.class, known.get(0));
        return db.queryForMap("SELECT * FROM support_ticket WHERE id=? FOR UPDATE", id);
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public Applied staffReply(String id, String request, String body, boolean internal, CustomUserDetails actor) {
        String username = authorize(actor, "support.reply"), b = body.trim();
        if (b.isEmpty()) throw fail(400, "內容不可空白");
        var t = lockTicket(id);
        var prior = db.queryForList("SELECT body,staff_id,internal FROM support_message WHERE ticket_id=? AND request_id=?", id, request);
        if (!prior.isEmpty()) {
            var p = prior.get(0);
            boolean wasInternal = p.get("internal") instanceof Boolean x ? x : p.get("internal") instanceof Number n && n.intValue() != 0;
            Integer by = p.get("staff_id") == null ? null : ((Number) p.get("staff_id")).intValue();
            if (!b.equals(p.get("body")) || !Objects.equals(actor.getId(), by) || internal != wasInternal)
                throw fail(409, "操作編號已用於不同內容");
            return new Applied(detail(id, true), true);
        }
        if ("CLOSED".equals(t.get("status")) && !internal) throw fail(409, "客服單已結案");
        db.update("INSERT INTO support_message(id,ticket_id,request_id,author_type,staff_id,staff_username,internal,body) VALUES(?,?,?,'STAFF',?,?,?,?)",
                UUID.randomUUID().toString(), id, request, actor.getId(), username, internal, b);
        if (!internal) db.update("UPDATE support_ticket SET status='WAITING_CUSTOMER',version=version+1,updated_at=CURRENT_TIMESTAMP(6) WHERE id=?", id);
        return new Applied(detail(id, true), false);
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public Applied change(String id, String action, String request, int expectedVersion, String status, Integer assignee, String note, CustomUserDetails actor) {
        String username = authorize(actor, action.equals("STATUS") ? "support.status" : "support.assign"), n = note.trim();
        if (n.isEmpty()) throw fail(400, "原因不可空白");
        if (action.equals("STATUS") && (status == null || !STATUSES.contains(status))) throw fail(400, "狀態不正確");
        if (action.equals("ASSIGN") && assignee != null && db.queryForObject("SELECT COUNT(*) FROM user WHERE id=?", Integer.class, assignee) == 0) throw fail(400, "找不到員工");
        var t = lockTicket(id);
        var prior = db.queryForList("SELECT * FROM support_ticket_event WHERE request_id=?", request);
        if (!prior.isEmpty()) {
            var e = prior.get(0);
            boolean same = ((Number) e.get("actor_id")).intValue() == actor.getId() && id.equals(e.get("ticket_id")) && action.equals(e.get("action")) && n.equals(e.get("note"))
                    && ((Number) e.get("before_version")).intValue() == expectedVersion
                    && (action.equals("STATUS") ? status.equals(e.get("after_status")) : Objects.equals(assignee, e.get("after_assignee") == null ? null : ((Number) e.get("after_assignee")).intValue()));
            if (!same) throw fail(409, "操作編號已用於不同內容");
            return new Applied(detail(id, true), true);
        }
        int v = ((Number) t.get("version")).intValue();
        if (v != expectedVersion) throw fail(409, "客服單已更新，請重新載入");
        String before = (String) t.get("status"); Integer beforeAssignee = t.get("assignee_id") == null ? null : ((Number) t.get("assignee_id")).intValue();
        String after = action.equals("STATUS") ? status : before; Integer afterAssignee = action.equals("ASSIGN") ? assignee : beforeAssignee;
        db.update("UPDATE support_ticket SET status=?,assignee_id=?,version=version+1,updated_at=CURRENT_TIMESTAMP(6) WHERE id=?", after, afterAssignee, id);
        try {
            db.update("INSERT INTO support_ticket_event(request_id,ticket_id,actor_id,actor_username,action,before_status,after_status,before_assignee,after_assignee,note,before_version,after_version) VALUES(?,?,?,?,?,?,?,?,?,?,?,?)",
                    request, id, actor.getId(), username, action, before, after, beforeAssignee, afterAssignee, n, v, v + 1);
        } catch (DuplicateKeyException e) { throw fail(409, "操作編號已被使用"); }
        return new Applied(detail(id, true), false);
    }
}
