package dev.jackteng.figura.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.jackteng.figura.dto.ShippingPayloads.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.*;

/**
 * 出貨單的唯讀投影（不依賴任何訂單服務，避免循環依賴）。狀態一律由組成訂單推導，
 * 所以舊的單筆出貨端點與出貨單端點看到的是同一份事實。
 */
@Service
public class ShipmentReader {
    private static final ZoneOffset TAIPEI = ZoneOffset.ofHours(8);
    private final JdbcTemplate db; private final ObjectMapper json;
    public ShipmentReader(JdbcTemplate db, ObjectMapper json) { this.db = db; this.json = json; }
    private static OffsetDateTime time(Object v) { return v == null ? null : (v instanceof java.sql.Timestamp t ? t.toLocalDateTime() : (LocalDateTime) v).atOffset(TAIPEI); }

    /** 組成訂單的目前狀態（含是否可出貨）。closed＝已取消／逾時／退款，不再計入出貨。 */
    record Member(String kind, String id, String title, String status, String fulfillment, boolean closed, boolean ready, String waiting, int version) {}

    List<Member> members(String shipment) {
        var out = new ArrayList<Member>();
        for (var row : db.queryForList("SELECT stock_order_id, preorder_order_id FROM shipment_item WHERE shipment_id=? ORDER BY position", shipment)) {
            String stock = (String) row.get("stock_order_id"), pre = (String) row.get("preorder_order_id");
            if (stock != null) {
                var o = db.queryForMap("""
                    SELECT o.status, o.fulfillment_status, o.version,
                           (SELECT MIN(l.title) FROM customer_order_line l WHERE l.order_id=o.id) title,
                           EXISTS(SELECT 1 FROM mock_payment p WHERE p.order_id=o.id AND p.status='REVIEW_REQUIRED'
                                  AND NOT EXISTS(SELECT 1 FROM mock_refund_event r WHERE r.payment_id=p.id AND r.action='REVIEW_REFUND')) review,
                           EXISTS(SELECT 1 FROM mock_order_return t WHERE t.order_id=o.id AND t.status IN ('REQUESTED','RECEIVED')) returning
                      FROM customer_order o WHERE o.id=?""", stock);
                String status = (String) o.get("status"), f = (String) o.get("fulfillment_status");
                boolean closed = Set.of("CANCELLED", "EXPIRED", "REFUNDED").contains(status), flagged = truthy(o.get("review")) || truthy(o.get("returning"));
                boolean ready = "PAID".equals(status) && "UNFULFILLED".equals(f) && !flagged;
                String waiting = closed || ready || !"UNFULFILLED".equals(f) ? null : flagged ? "REVIEW" : "PAYMENT";
                out.add(new Member("STOCK", stock, (String) o.get("title"), status, f, closed, ready, waiting, ((Number) o.get("version")).intValue()));
            } else {
                var o = db.queryForMap("""
                    SELECT p.status, p.fulfillment_status, p.arrival_status, p.allocated_quantity, p.quantity, p.product_title, p.batch_name, p.version, p.payment_mode, p.balance_status,
                           EXISTS(SELECT 1 FROM preorder_mock_payment m LEFT JOIN preorder_refund_event f ON f.payment_id=m.id
                                  WHERE m.order_id=p.id AND m.status='REVIEW_REQUIRED' AND f.request_id IS NULL) review
                      FROM preorder_order p WHERE p.id=?""", pre);
                String status = (String) o.get("status"), f = (String) o.get("fulfillment_status");
                boolean closed = Set.of("CANCELLED", "EXPIRED", "REFUNDED").contains(status), review = truthy(o.get("review"));
                boolean allocated = "ARRIVED".equals(o.get("arrival_status")) && Objects.equals(o.get("allocated_quantity"), o.get("quantity"));
                boolean ready = "PAID".equals(status) && "UNFULFILLED".equals(f) && allocated && !review;
                String waiting = closed || ready || !"UNFULFILLED".equals(f) ? null : review ? "REVIEW" : !"PAID".equals(status) ? "PAYMENT"
                        : "DEPOSIT".equals(o.get("payment_mode")) && !"PAID".equals(o.get("balance_status")) ? "BALANCE" : "ARRIVAL";
                out.add(new Member("PREORDER", pre, o.get("product_title") + "・" + o.get("batch_name"), status, f, closed, ready, waiting, ((Number) o.get("version")).intValue()));
            }
        }
        return out;
    }
    private static boolean truthy(Object v) { return v instanceof Boolean b ? b : v instanceof Number n && n.intValue() != 0; }

    /** 推導狀態；waitingFor 只在 WAITING 時提供（PAYMENT／BALANCE／ARRIVAL／REVIEW）。 */
    static String[] status(List<Member> members) {
        var active = members.stream().filter(m -> !m.closed()).toList();
        if (active.isEmpty()) return new String[]{"CANCELLED", null};
        if (active.stream().allMatch(m -> "DELIVERED".equals(m.fulfillment()))) return new String[]{"DELIVERED", null};
        if (active.stream().allMatch(m -> !"UNFULFILLED".equals(m.fulfillment()))) return new String[]{"SHIPPED", null};
        if (active.stream().allMatch(Member::ready)) return new String[]{"READY", null};
        // 依優先序回報還在等什麼
        for (String w : List.of("REVIEW", "PAYMENT", "BALANCE", "ARRIVAL")) if (active.stream().anyMatch(m -> w.equals(m.waiting()))) return new String[]{"WAITING", w};
        return new String[]{"WAITING", null};
    }

    Shipment view(Map<String, Object> s) {
        String id = (String) s.get("id");
        var members = members(id); var st = status(members);
        Map<String, Object> destination;
        try { destination = json.readValue((String) s.get("destination"), new TypeReference<>() {}); } catch (Exception e) { throw new IllegalStateException(e); }
        String tracking = (String) s.get("tracking_number");
        if (tracking == null) tracking = trackingOf(members);
        return new Shipment(id, (String) s.get("method"), (String) s.get("method_name"), (String) s.get("strategy"), ((Number) s.get("fee_twd")).intValue(),
                ((Number) s.get("goods_twd")).longValue(), st[0], st[1], (String) s.get("carrier"), tracking, time(s.get("shipped_at")), time(s.get("delivered_at")),
                destination, members.stream().map(m -> new ShipmentOrder(m.kind(), m.id(), m.title(), m.status(), m.fulfillment())).toList(),
                ((Number) s.get("version")).intValue(), time(s.get("created_at")));
    }
    /** 單筆出貨端點出貨的訂單：沿用訂單上的測試貨件號。 */
    private String trackingOf(List<Member> members) {
        for (var m : members) {
            var t = m.kind().equals("STOCK")
                    ? db.queryForList("SELECT shipment_tracking_id FROM customer_order WHERE id=? AND shipment_tracking_id IS NOT NULL", String.class, m.id())
                    : db.queryForList("SELECT tracking_number FROM preorder_order WHERE id=? AND tracking_number IS NOT NULL", String.class, m.id());
            if (!t.isEmpty()) return t.get(0);
        }
        return null;
    }
    static final String SELECT = "SELECT s.*, m.name method_name FROM shipment s JOIN shipping_method m ON m.code=s.method";

    /** 顧客／員工看訂單時附上的出貨單；舊訂單沒有出貨單時回 null。 */
    public Shipment forOrder(String kind, String orderId) {
        var rows = db.queryForList(SELECT + " JOIN shipment_item i ON i.shipment_id=s.id WHERE " + (kind.equals("STOCK") ? "i.stock_order_id=?" : "i.preorder_order_id=?"), orderId);
        return rows.isEmpty() ? null : view(rows.get(0));
    }
    /** 訂單是否屬於「多張訂單合併的出貨單」：這種訂單只能整張出貨單一起出貨。 */
    public boolean combined(String kind, String orderId) {
        return db.queryForObject("SELECT COUNT(*) FROM shipment_item i JOIN shipment_item o ON o.shipment_id=i.shipment_id WHERE "
                + (kind.equals("STOCK") ? "i.stock_order_id=?" : "i.preorder_order_id=?"), Integer.class, orderId) > 1;
    }
}
