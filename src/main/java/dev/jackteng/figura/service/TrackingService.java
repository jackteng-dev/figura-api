package dev.jackteng.figura.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.jackteng.figura.mapper.FulfillmentMapper;
import dev.jackteng.figura.pojo.CustomUserDetails;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.*;

/**
 * Commerce V2 Phase 6：出貨追蹤時間軸（延伸 V23 出貨單）。目前只有測試物流（source=TEST）：店長在後台手動新增事件，
 * 用來演練顧客端時間軸與通知。正式物流需實作 {@link CarrierAdapter} 並由 adapter 寫入同一張表；在那之前不得宣稱為真實物流資訊。
 * 到店（超商）與配送異常會寫一則站內通知（outbox，order parent）。
 */
@Service
public class TrackingService {
    /** 正式物流接入點：依貨件號查詢承運商狀態，轉成本站事件碼。測試環境沒有實作。 */
    public interface CarrierAdapter {
        String carrier();
        List<Map<String, Object>> poll(String trackingNumber);
    }
    public static final Map<String, String> DEFAULT_TEXT = Map.of("PICKED_UP", "物流已收件", "IN_TRANSIT", "運送中", "ARRIVED_STORE", "已到取貨門市，請攜帶證件取貨",
            "OUT_FOR_DELIVERY", "配送中，今日送達", "EXCEPTION", "配送異常，客服會與你聯繫", "RETURNED", "包裹退回");
    private static final ZoneOffset TAIPEI = ZoneOffset.ofHours(8);
    private final JdbcTemplate db; private final FulfillmentMapper staff; private final ObjectMapper json; private final boolean mockEnabled;
    public TrackingService(JdbcTemplate db, FulfillmentMapper staff, ObjectMapper json, @Value("${shop.fulfillment.mock-enabled:false}") boolean mockEnabled) {
        this.db = db; this.staff = staff; this.json = json; this.mockEnabled = mockEnabled;
    }
    public record Event(String id, String code, String description, String source, OffsetDateTime occurredAt) {}
    public record Timeline(String shipmentId, String method, String carrier, String trackingNumber, boolean testCarrier, List<Event> events) {}

    private static ResponseStatusException fail(int s, String m) { return new ResponseStatusException(HttpStatus.valueOf(s), m); }
    private static OffsetDateTime time(Object v) { return v == null ? null : (v instanceof java.sql.Timestamp t ? t.toLocalDateTime() : (LocalDateTime) v).atOffset(TAIPEI); }

    private Timeline timeline(Map<String, Object> s) {
        String id = (String) s.get("id");
        var events = db.query("SELECT * FROM shipment_tracking_event WHERE shipment_id=? ORDER BY occurred_at,created_at,id", (r, n) -> new Event(r.getString("id"), r.getString("code"),
                r.getString("description"), r.getString("source"), time(r.getObject("occurred_at", LocalDateTime.class))), id);
        return new Timeline(id, (String) s.get("method"), (String) s.get("carrier"), tracking(s), true, events);
    }
    /** 出貨單的貨件號：整張出貨會寫在出貨單；單筆端點出貨者在訂單上。 */
    private String tracking(Map<String, Object> s) {
        if (s.get("tracking_number") != null) return (String) s.get("tracking_number");
        var t = db.queryForList("SELECT COALESCE(o.shipment_tracking_id,p.tracking_number) FROM shipment_item i LEFT JOIN customer_order o ON o.id=i.stock_order_id LEFT JOIN preorder_order p ON p.id=i.preorder_order_id"
                + " WHERE i.shipment_id=? AND COALESCE(o.shipment_tracking_id,p.tracking_number) IS NOT NULL ORDER BY i.position LIMIT 1", String.class, s.get("id"));
        return t.isEmpty() ? null : t.get(0);
    }
    private Map<String, Object> shipment(String id) {
        if (id == null || !id.matches("[0-9a-f-]{36}")) throw fail(404, "找不到出貨單");
        var rows = db.queryForList("SELECT * FROM shipment WHERE id=?", id);
        if (rows.isEmpty()) throw fail(404, "找不到出貨單"); return rows.get(0);
    }

    @Transactional(readOnly = true)
    public Timeline forCustomer(String customer, String id) {
        var s = shipment(id); if (!customer.equals(s.get("customer_id"))) throw fail(404, "找不到出貨單");
        return timeline(s);
    }
    @Transactional(readOnly = true) public Timeline forStaff(String id) { return timeline(shipment(id)); }

    /** 測試物流：新增一筆追蹤事件。需模擬配送啟用、已出貨（有貨件號）；到店只限超商、配送中只限宅配。 */
    @Transactional(isolation = Isolation.READ_COMMITTED)
    public Timeline add(String id, String request, String code, String description, CustomUserDetails actor) {
        if (!mockEnabled) throw fail(404, "模擬配送未啟用");
        String username = actor == null ? null : staff.lockActor(actor.getId());
        if (username == null || staff.lockPermission(actor.getId(), "shipments.tracking.mock").isEmpty()) throw fail(403, "員工或物流授權已失效");
        if (!DEFAULT_TEXT.containsKey(code)) throw fail(400, "事件代碼不正確");
        var known = shipment(id);
        db.queryForList("SELECT id FROM customer WHERE id=? FOR UPDATE", String.class, known.get("customer_id"));
        var s = db.queryForMap("SELECT * FROM shipment WHERE id=? FOR UPDATE", id);
        var prior = db.queryForList("SELECT shipment_id,code,description FROM shipment_tracking_event WHERE request_id=?", request);
        String text = description == null || description.isBlank() ? DEFAULT_TEXT.get(code) : description.trim();
        if (!prior.isEmpty()) {
            var p = prior.get(0);
            if (!id.equals(p.get("shipment_id")) || !code.equals(p.get("code")) || !text.equals(p.get("description"))) throw fail(409, "操作編號已用於不同內容");
            return timeline(s);
        }
        if (tracking(s) == null) throw fail(409, "出貨單尚未出貨，沒有貨件號");
        if (s.get("merged_into") != null) throw fail(409, "出貨單已合併");
        if (code.equals("ARRIVED_STORE") && !"CVS".equals(s.get("method"))) throw fail(409, "宅配沒有到店事件");
        if (code.equals("OUT_FOR_DELIVERY") && !"HOME".equals(s.get("method"))) throw fail(409, "超商取貨沒有配送中事件");
        db.update("INSERT INTO shipment_tracking_event(id,shipment_id,request_id,code,description,actor_id,occurred_at) VALUES(?,?,?,?,?,?,CURRENT_TIMESTAMP(6))",
                UUID.randomUUID().toString(), id, request, code, text, actor.getId());
        if (code.equals("ARRIVED_STORE") || code.equals("EXCEPTION")) notifyCustomer(s, code, request);
        return timeline(s);
    }
    private void notifyCustomer(Map<String, Object> s, String code, String request) {
        var item = db.queryForList("SELECT stock_order_id,preorder_order_id FROM shipment_item WHERE shipment_id=? ORDER BY position LIMIT 1", s.get("id"));
        if (item.isEmpty()) return;
        String stock = (String) item.get(0).get("stock_order_id"), pre = (String) item.get(0).get("preorder_order_id"), kind = "SHIPMENT_" + code;
        var payload = new LinkedHashMap<String, Object>();
        payload.put("shipmentId", s.get("id")); payload.put("eventKind", kind); payload.put("orderId", stock != null ? stock : pre);
        String body; try { body = json.writeValueAsString(payload); } catch (Exception e) { throw new IllegalStateException(e); }
        db.update("INSERT INTO notification_outbox(id,event_kind,source_id,order_id,preorder_order_id,recipient_ref,payload) VALUES(?,?,?,?,?,?,?) ON DUPLICATE KEY UPDATE id=id",
                UUID.randomUUID().toString(), kind, UUID.nameUUIDFromBytes((kind + "|" + request).getBytes(StandardCharsets.UTF_8)).toString(), stock, pre, s.get("customer_id"), body);
    }
}
