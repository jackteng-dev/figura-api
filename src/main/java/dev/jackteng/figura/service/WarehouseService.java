package dev.jackteng.figura.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.jackteng.figura.dto.ShippingPayloads.Shipment;
import dev.jackteng.figura.dto.WarehousePayloads.*;
import dev.jackteng.figura.mapper.FulfillmentMapper;
import dev.jackteng.figura.pojo.CustomUserDetails;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.*;

/**
 * Commerce V2 Phase 5：出貨單合併與私人倉庫。
 * - 合併：同一顧客、尚未出貨、同配送方式與同目的地的 2–10 張出貨單，搬移訂單到新出貨單；訂單金額、付款、庫存都不動。
 *   新運費依合併後商品金額重算且不超過原運費總和；變少的差額只記錄為 PENDING_REVIEW 的運費調整，不自動退款或扣款。
 * - 暫存：顧客可把尚未出貨的出貨單暫存在私人倉庫（期限 storage-days），暫存中不可出貨；顧客可隨時取消暫存，員工可解除或延長。
 * 鎖序：customer → 出貨單（依 ID 排序），與 ShipmentService 的 customer 先鎖一致；員工操作 actor/grant → customer → 出貨單。
 */
@Service
public class WarehouseService {
    private static final ZoneOffset TAIPEI = ZoneOffset.ofHours(8);
    private final JdbcTemplate db; private final ObjectMapper json; private final ShipmentReader reader; private final ShippingService shipping;
    private final FulfillmentMapper staff; private final TransactionTemplate tx; private final int storageDays;

    public WarehouseService(JdbcTemplate db, ObjectMapper json, ShipmentReader reader, ShippingService shipping, FulfillmentMapper staff,
                            PlatformTransactionManager tm, @Value("${shop.warehouse.storage-days:60}") int storageDays) {
        this.db = db; this.json = json; this.reader = reader; this.shipping = shipping; this.staff = staff; this.storageDays = storageDays;
        this.tx = new TransactionTemplate(tm); this.tx.setIsolationLevel(Isolation.READ_COMMITTED.value());
    }
    private static ResponseStatusException fail(int s, String m) { return new ResponseStatusException(HttpStatus.valueOf(s), m); }
    private static OffsetDateTime time(Object v) { return v == null ? null : (v instanceof java.sql.Timestamp t ? t.toLocalDateTime() : (LocalDateTime) v).atOffset(TAIPEI); }
    private void lockCustomer(String c) { if (db.queryForList("SELECT id FROM customer WHERE id=? FOR UPDATE", String.class, c).isEmpty()) throw fail(401, "顧客登入狀態無效"); }
    private Map<String, Object> shipment(String id, boolean lock) {
        var rows = db.queryForList(ShipmentReader.SELECT + " WHERE s.id=?" + (lock ? " FOR UPDATE" : ""), id);
        if (rows.isEmpty()) throw fail(404, "找不到出貨單"); return rows.get(0);
    }
    private String encode(Object v) { try { return json.writeValueAsString(v); } catch (Exception e) { throw new IllegalStateException(e); } }
    private String hash(Object v) { try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(encode(v).getBytes(StandardCharsets.UTF_8))); } catch (Exception e) { throw new IllegalStateException(e); } }

    // ---- 顧客 ----
    private CustomerShipment customerView(Map<String, Object> s) {
        Shipment v = reader.view(s); var members = reader.members(v.id());
        String reason = blocked(v, members);
        boolean open = reason == null;
        return new CustomerShipment(v, open && v.heldAt() == null, v.heldAt() != null && v.mergedInto() == null, open, reason);
    }
    /** 不能暫存或合併的原因；null＝可以。 */
    private static String blocked(Shipment v, List<ShipmentReader.Member> members) {
        if (v.mergedInto() != null) return "已併入其他出貨單";
        if (!Set.of("WAITING", "READY").contains(v.status())) return "出貨單已出貨、送達或取消";
        if ("FEE".equals(v.waitingFor())) return "承擔運費的訂單已取消，請聯絡客服";
        if (members.stream().anyMatch(m -> !m.closed() && !"UNFULFILLED".equals(m.fulfillment()))) return "已有訂單出貨";
        if (members.stream().anyMatch(m -> !m.closed() && m.flagged())) return "有訂單付款待核對或退貨中";
        return null;
    }

    @Transactional(readOnly = true)
    public CustomerPage list(String customer, int page, int size) {
        if (page < 1 || page > 10000 || size < 1 || size > 50) throw fail(400, "分頁範圍不正確");
        var items = db.queryForList(ShipmentReader.SELECT + " WHERE s.customer_id=? AND s.merged_into IS NULL ORDER BY s.created_at DESC,s.id DESC LIMIT ? OFFSET ?", customer, size, (page - 1) * size)
                .stream().map(this::customerView).toList();
        return new CustomerPage(items, page, size, db.queryForObject("SELECT COUNT(*) FROM shipment WHERE customer_id=? AND merged_into IS NULL", Long.class, customer), storageDays);
    }

    private Optional<Map<String, Object>> prior(String request, String customer, String action, String fingerprint) {
        var rows = db.queryForList("SELECT * FROM shipment_customer_event WHERE request_id=?", request);
        if (rows.isEmpty()) return Optional.empty();
        var e = rows.get(0);
        if (!customer.equals(e.get("customer_id")) || !action.equals(e.get("action")) || !fingerprint.equals(e.get("fingerprint"))) throw fail(409, "操作編號已用於不同內容，請重新核對");
        return Optional.of(e);
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public Merged merge(String customer, Merge in) {
        var refs = new TreeMap<String, Integer>();
        for (var r : in.shipments()) if (refs.put(r.id(), r.expectedVersion()) != null) throw fail(400, "出貨單重複");
        String fp = hash(List.of("MERGE", refs));
        lockCustomer(customer);
        var old = prior(in.requestId(), customer, "MERGE", fp);
        if (old.isPresent()) { String id = (String) old.get().get("shipment_id"); return new Merged(customerView(shipment(id, false)), adjustment(id), true); }
        var rows = new ArrayList<Map<String, Object>>();
        for (var e : refs.entrySet()) {   // ID 排序鎖定
            var s = shipment(e.getKey(), true);
            if (!customer.equals(s.get("customer_id"))) throw fail(404, "找不到出貨單");
            if (((Number) s.get("version")).intValue() != e.getValue()) throw fail(409, "出貨單已變更，請重新整理後再合併");
            rows.add(s);
        }
        var first = rows.get(0);
        Map<String, Object> dest = destination(first);
        long goods = 0; int fees = 0; LocalDateTime heldUntil = null, heldFrom = null; boolean held = false;
        var items = new ArrayList<Map<String, Object>>();
        for (var s : rows) {
            var v = reader.view(s); var members = reader.members(v.id());
            String reason = blocked(v, members);
            if (reason != null) throw fail(409, "出貨單 " + v.id().substring(0, 8) + " 不能合併：" + reason);
            if (!first.get("method").equals(s.get("method"))) throw fail(409, "配送方式不同的出貨單不能合併");
            if (!dest.equals(destination(s))) throw fail(409, "收件地址或取貨門市不同的出貨單不能合併");
            goods += ((Number) s.get("goods_twd")).longValue(); fees += ((Number) s.get("fee_twd")).intValue();
            if (s.get("held_at") != null) {   // 合併後沿用最早的暫存起點與最早的期限
                held = true; LocalDateTime until = (LocalDateTime) toLocal(s.get("storage_until")), from = (LocalDateTime) toLocal(s.get("held_at"));
                if (heldUntil == null || until.isBefore(heldUntil)) heldUntil = until;
                if (heldFrom == null || from.isBefore(heldFrom)) heldFrom = from;
            }
            items.addAll(db.queryForList("SELECT stock_order_id,preorder_order_id FROM shipment_item WHERE shipment_id=? ORDER BY position", v.id()));
        }
        if (items.size() > 51) throw fail(409, "合併後訂單過多（上限 51 張）");
        var method = shipping.method((String) first.get("method"));
        int fee = Math.min(fees, ShippingService.fee(method, goods));   // 只會變少，不會多收
        String id = UUID.randomUUID().toString();
        db.update("INSERT INTO shipment(id,customer_id,group_id,method,strategy,destination,fee_twd,goods_twd,held_at,storage_until) VALUES(?,?,NULL,?,'COMBINED',?,?,?,?,?)",
                id, customer, first.get("method"), first.get("destination"), fee, goods, held ? heldFrom : null, held ? heldUntil : null);
        for (var s : rows) db.update("DELETE FROM shipment_item WHERE shipment_id=?", s.get("id"));
        int pos = 0;
        for (var it : items) db.update("INSERT INTO shipment_item(shipment_id,position,stock_order_id,preorder_order_id) VALUES(?,?,?,?)", id, pos++, it.get("stock_order_id"), it.get("preorder_order_id"));
        for (var s : rows) db.update("UPDATE shipment SET merged_into=?,held_at=NULL,storage_until=NULL,hold_reminded_at=NULL,version=version+1 WHERE id=?", id, s.get("id"));
        if (fees > fee) db.update("INSERT INTO shipping_adjustment(id,shipment_id,customer_id,before_fee_twd,after_fee_twd,credit_twd) VALUES(?,?,?,?,?,?)",
                UUID.randomUUID().toString(), id, customer, fees, fee, fees - fee);
        var detail = new LinkedHashMap<String, Object>(); detail.put("sources", refs.keySet()); detail.put("beforeFeeTwd", fees); detail.put("afterFeeTwd", fee);
        db.update("INSERT INTO shipment_customer_event(request_id,customer_id,action,shipment_id,fingerprint,detail) VALUES(?,?,?,?,?,?)", in.requestId(), customer, "MERGE", id, fp, encode(detail));
        return new Merged(customerView(shipment(id, false)), adjustment(id), false);
    }
    private static Object toLocal(Object v) { return v instanceof java.sql.Timestamp t ? t.toLocalDateTime() : v; }
    private Map<String, Object> destination(Map<String, Object> s) {
        try { return json.readValue((String) s.get("destination"), new TypeReference<>() {}); } catch (Exception e) { throw new IllegalStateException(e); }
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public Held hold(String customer, String id, HoldCommand in, boolean hold) {
        String action = hold ? "HOLD" : "RELEASE", fp = hash(List.of(action, id, in.expectedVersion()));
        lockCustomer(customer);
        if (prior(in.requestId(), customer, action, fp).isPresent()) return new Held(customerView(shipment(id, false)), true);
        var s = shipment(id, true);
        if (!customer.equals(s.get("customer_id"))) throw fail(404, "找不到出貨單");
        if (((Number) s.get("version")).intValue() != in.expectedVersion()) throw fail(409, "出貨單已變更，請重新整理");
        var view = customerView(s);
        if (hold) {
            if (!view.canHold()) throw fail(409, view.blockedReason() != null ? "不能暫存：" + view.blockedReason() : "出貨單已在暫存中");
            db.update("UPDATE shipment SET held_at=CURRENT_TIMESTAMP(6),storage_until=CURRENT_TIMESTAMP(6)+INTERVAL ? DAY,hold_reminded_at=NULL,version=version+1 WHERE id=?", storageDays, id);
        } else {
            if (!view.canRelease()) throw fail(409, "出貨單沒有暫存");
            db.update("UPDATE shipment SET held_at=NULL,storage_until=NULL,hold_reminded_at=NULL,version=version+1 WHERE id=?", id);
        }
        db.update("INSERT INTO shipment_customer_event(request_id,customer_id,action,shipment_id,fingerprint,detail) VALUES(?,?,?,?,?,?)", in.requestId(), customer, action, id, fp, "{}");
        return new Held(customerView(shipment(id, false)), false);
    }

    private Adjustment adjustment(String shipmentId) {
        var rows = db.queryForList("SELECT * FROM shipping_adjustment WHERE shipment_id=?", shipmentId);
        return rows.isEmpty() ? null : adjustmentOf(rows.get(0));
    }
    private static Adjustment adjustmentOf(Map<String, Object> r) {
        return new Adjustment((String) r.get("id"), (String) r.get("shipment_id"), (String) r.get("customer_id"), ((Number) r.get("before_fee_twd")).intValue(),
                ((Number) r.get("after_fee_twd")).intValue(), ((Number) r.get("credit_twd")).intValue(), (String) r.get("status"), ((Number) r.get("version")).intValue(),
                time(r.get("created_at")), time(r.get("decided_at")));
    }

    // ---- 員工 ----
    private String authorize(CustomUserDetails actor, String code) {
        String name = actor == null ? null : staff.lockActor(actor.getId());
        if (name == null || staff.lockPermission(actor.getId(), code).isEmpty()) throw fail(403, "員工或倉庫授權已失效");
        return name;
    }
    private Optional<OpsEvent> priorOps(String request, int actor, String target, String action, String note, Integer days, int version) {
        var rows = db.queryForList("SELECT * FROM shipment_ops_event WHERE request_id=?", request);
        if (rows.isEmpty()) return Optional.empty();
        var e = ops(rows.get(0));
        if (e.actorId() != actor || !e.targetId().equals(target) || !e.action().equals(action) || !e.note().equals(note) || !Objects.equals(e.days(), days) || e.beforeVersion() != version)
            throw fail(409, "操作編號已用於不同內容，請重新核對");
        return Optional.of(e);
    }
    private static OpsEvent ops(Map<String, Object> r) {
        return new OpsEvent((String) r.get("request_id"), (String) r.get("target_id"), ((Number) r.get("actor_id")).intValue(), (String) r.get("actor_username"), (String) r.get("action"),
                (String) r.get("note"), r.get("days") == null ? null : ((Number) r.get("days")).intValue(), ((Number) r.get("before_version")).intValue(), ((Number) r.get("after_version")).intValue(), time(r.get("created_at")));
    }
    private OpsEvent record(String request, String target, CustomUserDetails actor, String username, String action, String note, Integer days, int before) {
        try {
            db.update("INSERT INTO shipment_ops_event(request_id,target_id,actor_id,actor_username,action,note,days,before_version,after_version) VALUES(?,?,?,?,?,?,?,?,?)",
                    request, target, actor.getId(), username, action, note, days, before, before + 1);
        } catch (DuplicateKeyException e) { throw fail(409, "操作編號已被使用"); }
        return ops(db.queryForMap("SELECT * FROM shipment_ops_event WHERE request_id=?", request));
    }

    @Transactional(readOnly = true)
    public WarehousePage warehouse(int page, int size, boolean overdueOnly) {
        if (page < 1 || page > 10000 || size < 1 || size > 50) throw fail(400, "分頁範圍不正確");
        String where = " WHERE s.held_at IS NOT NULL AND s.merged_into IS NULL" + (overdueOnly ? " AND s.storage_until<CURRENT_TIMESTAMP(6)" : "");
        var items = db.queryForList(ShipmentReader.SELECT + where + " ORDER BY s.storage_until,s.id LIMIT ? OFFSET ?", size, (page - 1) * size).stream()
                .map(s -> new WarehouseItem(reader.view(s), (String) s.get("customer_id"), ((LocalDateTime) toLocal(s.get("storage_until"))).isBefore(LocalDateTime.now()))).toList();
        return new WarehousePage(items, page, size, db.queryForObject("SELECT COUNT(*) FROM shipment s" + where, Long.class));
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public StaffApplied staffHold(String id, String action, StaffCommand in, CustomUserDetails actor) {
        boolean extend = action.equals("EXTEND_HOLD");
        String username = authorize(actor, extend ? "warehouse.extend" : "warehouse.release"), note = in.note().trim();
        if (note.isBlank()) throw fail(400, "原因不可空白");
        if (extend && in.days() == null) throw fail(400, "請指定延長天數");
        Integer days = extend ? in.days() : null;
        var known = shipment(id, false);
        lockCustomer((String) known.get("customer_id"));
        var s = shipment(id, true);
        var old = priorOps(in.requestId(), actor.getId(), id, action, note, days, in.expectedVersion());
        if (old.isPresent()) return new StaffApplied(old.get(), true);
        int version = ((Number) s.get("version")).intValue();
        if (version != in.expectedVersion()) throw fail(409, "出貨單已變更，請重新整理");
        if (s.get("held_at") == null || s.get("merged_into") != null) throw fail(409, "出貨單沒有暫存");
        if (extend) db.update("UPDATE shipment SET storage_until=storage_until+INTERVAL ? DAY,hold_reminded_at=NULL,version=version+1 WHERE id=?", days, id);
        else db.update("UPDATE shipment SET held_at=NULL,storage_until=NULL,hold_reminded_at=NULL,version=version+1 WHERE id=?", id);
        return new StaffApplied(record(in.requestId(), id, actor, username, action, note, days, version), false);
    }

    @Transactional(readOnly = true)
    public AdjustmentPage adjustments(int page, int size, String status) {
        if (page < 1 || page > 10000 || size < 1 || size > 50) throw fail(400, "分頁範圍不正確");
        if (status != null && !Set.of("PENDING_REVIEW", "CREDITED_MOCK", "DISMISSED").contains(status)) throw fail(400, "狀態不正確");
        String where = " FROM shipping_adjustment WHERE (? IS NULL OR status=?)";
        return new AdjustmentPage(db.queryForList("SELECT *" + where + " ORDER BY created_at DESC,id DESC LIMIT ? OFFSET ?", status, status, size, (page - 1) * size).stream().map(WarehouseService::adjustmentOf).toList(),
                page, size, db.queryForObject("SELECT COUNT(*)" + where, Long.class, status, status));
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public StaffApplied decide(String id, Decide in, CustomUserDetails actor) {
        String username = authorize(actor, "shipping.adjustments.decide"), note = in.note().trim();
        if (note.isBlank()) throw fail(400, "原因不可空白");
        var rows = db.queryForList("SELECT * FROM shipping_adjustment WHERE id=? FOR UPDATE", id);
        if (rows.isEmpty()) throw fail(404, "找不到運費調整");
        var old = priorOps(in.requestId(), actor.getId(), id, in.decision(), note, null, in.expectedVersion());
        if (old.isPresent()) return new StaffApplied(old.get(), true);
        var a = adjustmentOf(rows.get(0));
        if (a.version() != in.expectedVersion()) throw fail(409, "運費調整已變更，請重新整理");
        if (!a.status().equals("PENDING_REVIEW")) throw fail(409, "運費調整已處理");
        db.update("UPDATE shipping_adjustment SET status=?,decided_at=CURRENT_TIMESTAMP(6),version=version+1 WHERE id=?", in.decision().equals("CREDIT_MOCK") ? "CREDITED_MOCK" : "DISMISSED", id);
        return new StaffApplied(record(in.requestId(), id, actor, username, in.decision(), note, null, a.version()), false);
    }

    // ---- 暫存到期提醒（排程或測試呼叫；每筆一個短交易） ----
    public int remind(int limit, int daysBefore) {
        var ids = db.queryForList("SELECT id FROM shipment WHERE held_at IS NOT NULL AND merged_into IS NULL AND hold_reminded_at IS NULL AND storage_until<=CURRENT_TIMESTAMP(6)+INTERVAL ? DAY ORDER BY storage_until,id LIMIT ?",
                String.class, daysBefore, Math.max(1, Math.min(limit, 500)));
        int n = 0;
        for (String id : ids) { Boolean ok = tx.execute(st -> remindOne(id, daysBefore)); if (Boolean.TRUE.equals(ok)) n++; }
        return n;
    }
    private boolean remindOne(String id, int daysBefore) {
        var rows = db.queryForList("SELECT customer_id,storage_until FROM shipment WHERE id=? AND held_at IS NOT NULL AND merged_into IS NULL AND hold_reminded_at IS NULL AND storage_until<=CURRENT_TIMESTAMP(6)+INTERVAL ? DAY FOR UPDATE", id, daysBefore);
        if (rows.isEmpty()) return false;
        var item = db.queryForList("SELECT stock_order_id,preorder_order_id FROM shipment_item WHERE shipment_id=? ORDER BY position LIMIT 1", id);
        if (item.isEmpty()) return false;
        String until = String.valueOf(toLocal(rows.get(0).get("storage_until")));
        var payload = new LinkedHashMap<String, Object>();
        payload.put("shipmentId", id); payload.put("eventKind", "WAREHOUSE_STORAGE_ENDING"); payload.put("storageUntil", until);
        String stock = (String) item.get(0).get("stock_order_id"), pre = (String) item.get(0).get("preorder_order_id");
        payload.put("orderId", stock != null ? stock : pre);
        String source = UUID.nameUUIDFromBytes(("WAREHOUSE|" + id + "|" + until).getBytes(StandardCharsets.UTF_8)).toString();
        db.update("INSERT INTO notification_outbox(id,event_kind,source_id,order_id,preorder_order_id,recipient_ref,payload) VALUES(?,?,?,?,?,?,?) ON DUPLICATE KEY UPDATE id=id",
                UUID.randomUUID().toString(), "WAREHOUSE_STORAGE_ENDING", source, stock, pre, rows.get(0).get("customer_id"), encode(payload));
        db.update("UPDATE shipment SET hold_reminded_at=CURRENT_TIMESTAMP(6) WHERE id=?", id);
        return true;
    }
}
