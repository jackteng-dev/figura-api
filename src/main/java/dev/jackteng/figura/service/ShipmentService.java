package dev.jackteng.figura.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.jackteng.figura.dto.ShippingPayloads.*;
import dev.jackteng.figura.mapper.FulfillmentMapper;
import dev.jackteng.figura.pojo.CustomUserDetails;
import dev.jackteng.figura.pojo.FulfillmentEvent;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.*;

/**
 * 出貨單（Phase 5）：整張出貨單一起出貨／送達，透過測試物流 adapter（carrier=TEST、貨件號 MOCK-…），不會實際寄送。
 * <p>
 * 每張組成訂單照樣寫入既有的單筆履約紀錄、版本與通知（現貨 order_fulfillment_event、預購 preorder_fulfillment_event＋批次出貨數），
 * 所以訂單詳情、訂單中心與舊的單筆端點看到的是同一份事實。
 * <p>
 * 鎖序：actor/grant → 顧客 → 現貨訂單 → 預購批次（ID 排序）→ 預購訂單（ID 排序）→ 出貨單；
 * 與付款（customer → order／customer → batch → order）、單筆履約（actor → customer → batch → order）一致。
 */
@Service
public class ShipmentService {
    private static final ZoneOffset TAIPEI = ZoneOffset.ofHours(8);
    private final JdbcTemplate db; private final ObjectMapper json; private final ShipmentReader reader; private final FulfillmentMapper staff;
    private final PreorderOrderService preorders; private final NotificationCapture notifications; private final boolean mockEnabled;
    public ShipmentService(JdbcTemplate db, ObjectMapper json, ShipmentReader reader, FulfillmentMapper staff, PreorderOrderService preorders,
                           NotificationCapture notifications, @Value("${shop.fulfillment.mock-enabled:false}") boolean mockEnabled) {
        this.db = db; this.json = json; this.reader = reader; this.staff = staff; this.preorders = preorders; this.notifications = notifications; this.mockEnabled = mockEnabled;
    }
    private static ResponseStatusException fail(int code, String message) { return new ResponseStatusException(HttpStatus.valueOf(code), message); }
    private static void uuid(String id) { try { if (!UUID.fromString(id).toString().equals(id)) throw new IllegalArgumentException(); } catch (Exception e) { throw fail(400, "出貨單編號不正確"); } }
    private final RowMapper<Event> events = (r, n) -> new Event(r.getString("request_id"), r.getString("shipment_id"), r.getInt("actor_id"), r.getString("actor_username"),
            r.getString("action"), r.getString("note"), r.getInt("before_version"), r.getInt("after_version"), r.getString("tracking_number"),
            r.getObject("created_at", LocalDateTime.class).atOffset(TAIPEI));

    private Map<String, Object> row(String id, boolean lock) {
        uuid(id);
        var rows = db.queryForList(ShipmentReader.SELECT + " WHERE s.id=?" + (lock ? " FOR UPDATE" : ""), id);
        if (rows.isEmpty()) throw fail(404, "找不到出貨單");
        return rows.get(0);
    }
    private Detail detail(Map<String, Object> s) {
        var v = reader.view(s); var members = reader.members(v.id());
        boolean clean = members.stream().filter(m -> !m.closed()).noneMatch(ShipmentReader.Member::flagged);
        boolean ship = v.status().equals("READY") || ("FEE".equals(v.waitingFor()) && ShipmentReader.allReady(members));
        return new Detail(v, mockEnabled, mockEnabled && ship, mockEnabled && clean && v.status().equals("SHIPPED"),
                db.query("SELECT * FROM shipment_event WHERE shipment_id=? ORDER BY created_at,request_id", events, v.id()));
    }
    @Transactional(readOnly = true) public Detail detail(String id) { return detail(row(id, false)); }
    @Transactional(readOnly = true) public Page list(int page, int size) {
        if (page < 1 || page > 100000 || size < 1 || size > 50) throw fail(400, "分頁參數不正確");
        var items = db.queryForList(ShipmentReader.SELECT + " ORDER BY s.created_at DESC, s.id DESC LIMIT ? OFFSET ?", size, (page - 1) * size).stream().map(reader::view).toList();
        return new Page(items, page, size, db.queryForObject("SELECT COUNT(*) FROM shipment", Long.class));
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public Applied command(String id, String action, Command in, CustomUserDetails actor) {
        if (!mockEnabled) throw fail(404, "模擬配送未啟用");
        if (!Set.of("SHIP", "DELIVER").contains(action)) throw fail(400, "不支援的出貨操作");
        String username = actor == null ? null : staff.lockActor(actor.getId());
        if (username == null || staff.lockPermission(actor.getId(), action.equals("SHIP") ? "shipments.ship.mock" : "shipments.deliver.mock").isEmpty()) throw fail(403, "員工或出貨授權已失效");
        String note = in.note().trim(); if (note.isBlank()) throw fail(400, "出貨原因不可空白");
        boolean waive = action.equals("SHIP") && Boolean.TRUE.equals(in.waiveUnpaidFee());
        if (waive) note = "【免收未付運費】" + note;
        var known = row(id, false);
        String customer = (String) known.get("customer_id");
        db.queryForList("SELECT id FROM customer WHERE id=? FOR UPDATE", String.class, customer);
        var items = db.queryForList("SELECT stock_order_id, preorder_order_id FROM shipment_item WHERE shipment_id=?", id);
        items.stream().map(r -> (String) r.get("stock_order_id")).filter(Objects::nonNull).sorted()
                .forEach(o -> db.queryForList("SELECT id FROM customer_order WHERE id=? FOR UPDATE", String.class, o));
        var pre = items.stream().map(r -> (String) r.get("preorder_order_id")).filter(Objects::nonNull).sorted().toList();
        if (!pre.isEmpty()) {
            var batches = new TreeSet<String>();
            for (String o : pre) batches.add(db.queryForObject("SELECT batch_id FROM preorder_order WHERE id=?", String.class, o));
            for (String b : batches) db.queryForList("SELECT id FROM preorder_batch WHERE id=? FOR UPDATE", String.class, b);
            for (String o : pre) db.queryForList("SELECT id FROM preorder_order WHERE id=? FOR UPDATE", String.class, o);
        }
        var s = row(id, true);
        var old = db.query("SELECT * FROM shipment_event WHERE request_id=?", events, in.requestId());
        if (!old.isEmpty()) {
            var e = old.get(0);
            if (e.actorId() != actor.getId() || !e.shipmentId().equals(id) || !e.action().equals(action) || e.beforeVersion() != in.expectedVersion() || !e.note().equals(note))
                throw fail(409, "出貨操作編號已用於不同內容，請重新核對");
            return new Applied(e, detail(s), true);
        }
        int version = ((Number) s.get("version")).intValue();
        if (version != in.expectedVersion()) throw fail(409, "出貨單已變更，請重新核對");
        var members = reader.members(id);
        String status = ShipmentReader.status(members)[0];
        String tracking;
        if (action.equals("SHIP")) {
            boolean feeOnly = ShipmentReader.unpaidFee(members) && ShipmentReader.allReady(members);
            if (feeOnly && !waive) throw fail(409, "承擔運費的訂單已取消或退款，運費未收；請店長確認後選擇免收運費再出貨");
            if (!status.equals("READY") && !feeOnly) throw fail(409, "出貨單還有訂單未付款、未付尾款、未到貨或待核對，不能出貨");
            tracking = "MOCK-" + UUID.randomUUID();
            for (var m : members) if (!m.closed()) ship(m, customer, in.requestId(), actor, username, note, tracking);
            db.update("UPDATE shipment SET tracking_number=?,shipped_at=CURRENT_TIMESTAMP(6),version=version+1 WHERE id=?", tracking, id);
        } else {
            if (!status.equals("SHIPPED")) throw fail(409, "出貨單需全部已出貨才能標記送達");
            // 與單筆端點相同的守門：有付款待核對或退貨中的訂單不可送達
            if (members.stream().filter(m -> !m.closed()).anyMatch(ShipmentReader.Member::flagged)) throw fail(409, "出貨單有訂單付款待核對或退貨中，暫停送達");
            tracking = (String) s.get("tracking_number");
            if (tracking == null) {
                // 單一訂單出貨單經舊的單筆端點出貨：現貨沿用訂單的測試貨件號與出貨時間；預購的舊格式貨件號請用單筆端點送達
                tracking = reader.view(s).trackingNumber();
                if (tracking == null || !tracking.startsWith("MOCK-") || tracking.length() > 41) throw fail(409, "此出貨單已用單筆端點出貨，請用單筆端點標記送達");
                db.update("UPDATE shipment SET tracking_number=?,shipped_at=(SELECT MIN(t) FROM (SELECT o.shipped_at t FROM customer_order o JOIN shipment_item i ON i.stock_order_id=o.id WHERE i.shipment_id=? UNION ALL SELECT p.shipped_at FROM preorder_order p JOIN shipment_item i ON i.preorder_order_id=p.id WHERE i.shipment_id=?) x) WHERE id=?", tracking, id, id, id);
            }
            for (var m : members) if (!m.closed() && !"DELIVERED".equals(m.fulfillment())) deliver(m, customer, in.requestId(), actor, username, note, tracking);
            db.update("UPDATE shipment SET delivered_at=CURRENT_TIMESTAMP(6),version=version+1 WHERE id=?", id);
        }
        try {
            db.update("INSERT INTO shipment_event(request_id,shipment_id,actor_id,actor_username,action,note,before_version,after_version,tracking_number) VALUES(?,?,?,?,?,?,?,?,?)",
                    in.requestId(), id, actor.getId(), username, action, note, version, version + 1, tracking);
        } catch (DuplicateKeyException e) { throw fail(409, "出貨操作編號或此出貨單的操作已保存，請重新核對"); }
        return new Applied(db.queryForObject("SELECT * FROM shipment_event WHERE request_id=?", events, in.requestId()), detail(row(id, false)), false);
    }

    private static String derived(String request, String order) { return UUID.nameUUIDFromBytes((request + "|" + order).getBytes(StandardCharsets.UTF_8)).toString(); }
    private String hash(Object v) { try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(json.writeValueAsBytes(v))); } catch (Exception e) { throw new IllegalStateException(e); } }

    private void ship(ShipmentReader.Member m, String customer, String request, CustomUserDetails actor, String username, String note, String tracking) {
        if (m.kind().equals("STOCK")) {
            if (staff.ship(m.id(), tracking, m.version()) != 1) throw fail(409, "訂單配送狀態已變更，請重新核對");
            stockEvent(m, request, actor, username, note, "SHIP", "UNFULFILLED", "SHIPPED", tracking);
            notifications.enqueue(staff.find(m.id()), "ORDER_SHIPPED", m.id());
        } else preorderStep(m, customer, request, actor, username, note, "SHIP", tracking);
    }
    private void deliver(ShipmentReader.Member m, String customer, String request, CustomUserDetails actor, String username, String note, String tracking) {
        if (m.kind().equals("STOCK")) {
            if (staff.deliver(m.id(), m.version()) != 1) throw fail(409, "訂單配送狀態已變更，請重新核對");
            String own = staff.find(m.id()).getShipmentTrackingId();
            stockEvent(m, request, actor, username, note, "DELIVER", "SHIPPED", "DELIVERED", own == null ? tracking : own);
            notifications.enqueue(staff.find(m.id()), "ORDER_DELIVERED", m.id());
        } else preorderStep(m, customer, request, actor, username, note, "DELIVER", tracking);
    }
    private void stockEvent(ShipmentReader.Member m, String request, CustomUserDetails actor, String username, String note, String action, String before, String after, String tracking) {
        var e = new FulfillmentEvent();
        e.setRequestId(derived(request, m.id())); e.setOrderId(m.id()); e.setActorId(actor.getId()); e.setActorUsername(username); e.setAction(action);
        e.setNote(note); e.setBeforeVersion(m.version()); e.setAfterVersion(m.version() + 1); e.setBeforeStatus(before); e.setAfterStatus(after); e.setTrackingId(tracking);
        try { staff.append(e); } catch (DuplicateKeyException ex) { throw fail(409, "訂單已有相同的履約紀錄，請重新核對"); }
    }
    /** 預購：與單筆履約相同的批次出貨數、訂單版本與不可變紀錄。 */
    private void preorderStep(ShipmentReader.Member m, String customer, String request, CustomUserDetails actor, String username, String note, String action, String tracking) {
        var o = db.queryForMap("SELECT batch_id, quantity, version, tracking_number FROM preorder_order WHERE id=?", m.id());
        String batch = (String) o.get("batch_id"); int qty = ((Number) o.get("quantity")).intValue(), ov = ((Number) o.get("version")).intValue();
        var b = db.queryForMap("SELECT received_quantity, allocated_quantity, shipped_quantity, version FROM preorder_batch WHERE id=?", batch);
        int received = ((Number) b.get("received_quantity")).intValue(), allocated = ((Number) b.get("allocated_quantity")).intValue(), shipped = ((Number) b.get("shipped_quantity")).intValue(), bv = ((Number) b.get("version")).intValue();
        String own = action.equals("SHIP") ? tracking : (String) o.get("tracking_number");
        if (action.equals("SHIP")) {
            if (shipped + qty > allocated) throw fail(409, "預購批次出貨數超過已分配數，請重新核對");
            db.update("UPDATE preorder_batch SET shipped_quantity=shipped_quantity+?,version=version+1,updated_at=CURRENT_TIMESTAMP(6) WHERE id=?", qty, batch);
            if (db.update("UPDATE preorder_order SET fulfillment_status='SHIPPED',tracking_number=?,shipped_at=CURRENT_TIMESTAMP(6),version=version+1 WHERE id=? AND fulfillment_status='UNFULFILLED' AND status='PAID'", tracking, m.id()) != 1) throw fail(409, "預購配送狀態已變更，請重新核對");
        } else {
            db.update("UPDATE preorder_batch SET version=version+1,updated_at=CURRENT_TIMESTAMP(6) WHERE id=?", batch);
            if (db.update("UPDATE preorder_order SET fulfillment_status='DELIVERED',delivered_at=CURRENT_TIMESTAMP(6),version=version+1 WHERE id=? AND fulfillment_status='SHIPPED' AND status='PAID'", m.id()) != 1) throw fail(409, "預購配送狀態已變更，請重新核對");
        }
        String rid = derived(request, m.id());
        try {
            db.update("INSERT INTO preorder_fulfillment_event(request_id,batch_id,order_id,actor_id,actor_username,action,fingerprint,note,quantity,received_before,received_after,allocated_before,allocated_after,shipped_before,shipped_after,batch_version_before,batch_version_after,order_version_before,order_version_after,tracking_number) VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)",
                    rid, batch, m.id(), actor.getId(), username, action, hash(List.of("SHIPMENT", request, m.id(), action)), note, qty, received, received, allocated, allocated,
                    shipped, action.equals("SHIP") ? shipped + qty : shipped, bv, bv + 1, ov, ov + 1, own);
        } catch (DuplicateKeyException ex) { throw fail(409, "預購已有相同的履約紀錄，請重新核對"); }
        notifications.enqueuePreorder(customer, preorders.detail(customer, m.id()), action.equals("SHIP") ? "PREORDER_SHIPPED" : "PREORDER_DELIVERED", rid);
    }
}
