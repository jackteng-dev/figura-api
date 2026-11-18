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
    /** flagged＝有未處理付款核對或退貨中（不可出貨也不可送達）；shippingTwd＝這張訂單承擔的運費。 */
    record Member(String kind, String id, String title, String status, String fulfillment, boolean closed, boolean ready, String waiting, int version,
                  boolean flagged, int shippingTwd) {}

    List<Member> members(String shipment) {
        var out = new ArrayList<Member>();
        for (var row : db.queryForList("SELECT stock_order_id, preorder_order_id FROM shipment_item WHERE shipment_id=? ORDER BY position", shipment)) {
            String stock = (String) row.get("stock_order_id"), pre = (String) row.get("preorder_order_id");
            if (stock != null) {
                var o = db.queryForMap("""
                    SELECT o.status, o.fulfillment_status, o.version, o.shipping_twd,
                           (SELECT MIN(l.title) FROM customer_order_line l WHERE l.order_id=o.id) title,
                           EXISTS(SELECT 1 FROM mock_payment p WHERE p.order_id=o.id AND p.status='REVIEW_REQUIRED'
                                  AND NOT EXISTS(SELECT 1 FROM mock_refund_event r WHERE r.payment_id=p.id AND r.action='REVIEW_REFUND')) review,
                           EXISTS(SELECT 1 FROM mock_order_return t WHERE t.order_id=o.id AND t.status IN ('REQUESTED','RECEIVED')) returning
                      FROM customer_order o WHERE o.id=?""", stock);
                String status = (String) o.get("status"), f = (String) o.get("fulfillment_status");
                boolean closed = Set.of("CANCELLED", "EXPIRED", "REFUNDED").contains(status), flagged = truthy(o.get("review")) || truthy(o.get("returning"));
                boolean ready = "PAID".equals(status) && "UNFULFILLED".equals(f) && !flagged;
                String waiting = closed || ready || !"UNFULFILLED".equals(f) ? null : flagged ? "REVIEW" : "PAYMENT";
                out.add(new Member("STOCK", stock, (String) o.get("title"), status, f, closed, ready, waiting, ((Number) o.get("version")).intValue(), flagged, ((Number) o.get("shipping_twd")).intValue()));
            } else {
                var o = db.queryForMap("""
                    SELECT p.status, p.fulfillment_status, p.arrival_status, p.allocated_quantity, p.quantity, p.product_title, p.batch_name, p.version, p.payment_mode, p.balance_status, p.shipping_twd,
                           EXISTS(SELECT 1 FROM preorder_mock_payment m LEFT JOIN preorder_refund_event f ON f.payment_id=m.id
                                  WHERE m.order_id=p.id AND m.status='REVIEW_REQUIRED' AND f.request_id IS NULL) review
                      FROM preorder_order p WHERE p.id=?""", pre);
                String status = (String) o.get("status"), f = (String) o.get("fulfillment_status");
                boolean closed = Set.of("CANCELLED", "EXPIRED", "REFUNDED").contains(status), review = truthy(o.get("review"));
                boolean allocated = "ARRIVED".equals(o.get("arrival_status")) && Objects.equals(o.get("allocated_quantity"), o.get("quantity"));
                boolean ready = "PAID".equals(status) && "UNFULFILLED".equals(f) && allocated && !review;
                String waiting = closed || ready || !"UNFULFILLED".equals(f) ? null : review ? "REVIEW" : !"PAID".equals(status) ? "PAYMENT"
                        : "DEPOSIT".equals(o.get("payment_mode")) && !"PAID".equals(o.get("balance_status")) ? "BALANCE" : "ARRIVAL";
                out.add(new Member("PREORDER", pre, o.get("product_title") + "・" + o.get("batch_name"), status, f, closed, ready, waiting, ((Number) o.get("version")).intValue(), review, ((Number) o.get("shipping_twd")).intValue()));
            }
        }
        return out;
    }
    /** 承擔運費的訂單在出貨前結束（取消、逾時或退款）且還有其他訂單要寄。 */
    static boolean unpaidFee(List<Member> members) {
        return members.stream().anyMatch(m -> m.closed() && "UNFULFILLED".equals(m.fulfillment()) && m.shippingTwd() > 0)
                && members.stream().anyMatch(m -> !m.closed());
    }
    /** 出貨前的有效訂單都已可出貨（忽略運費問題）。 */
    static boolean allReady(List<Member> members) {
        var active = members.stream().filter(m -> !m.closed()).toList();
        return !active.isEmpty() && active.stream().allMatch(Member::ready);
    }
    private static boolean truthy(Object v) { return v instanceof Boolean b ? b : v instanceof Number n && n.intValue() != 0; }

    /**
     * 推導狀態；waitingFor 在 WAITING 時說明原因（FEE／REVIEW／PAYMENT／BALANCE／ARRIVAL）。
     * 只排除「出貨前就結束」的訂單：出貨後才退款／退貨的訂單仍保留出貨與送達歷史。
     * FEE：承擔運費的訂單在出貨前結束，運費沒有收到，需店長明確決定免收才可出貨。
     */
    static String[] status(List<Member> members) {
        var relevant = members.stream().filter(m -> !(m.closed() && "UNFULFILLED".equals(m.fulfillment()))).toList();
        if (relevant.isEmpty()) return new String[]{"CANCELLED", null};
        if (relevant.stream().allMatch(m -> "DELIVERED".equals(m.fulfillment()))) return new String[]{"DELIVERED", null};
        if (relevant.stream().allMatch(m -> !"UNFULFILLED".equals(m.fulfillment()))) return new String[]{"SHIPPED", null};
        var active = members.stream().filter(m -> !m.closed()).toList();
        if (unpaidFee(members)) return new String[]{"WAITING", "FEE"};
        if (active.stream().allMatch(Member::ready)) return new String[]{"READY", null};
        // 依優先序回報還在等什麼
        for (String w : List.of("REVIEW", "PAYMENT", "BALANCE", "ARRIVAL")) if (active.stream().anyMatch(m -> w.equals(m.waiting()))) return new String[]{"WAITING", w};
        return new String[]{"WAITING", null};
    }

    Shipment view(Map<String, Object> s) {
        String id = (String) s.get("id");
        var members = members(id); var st = status(members);
        // 經單筆端點出貨的訂單：出貨／送達時間取自訂單
        Object shipped = s.get("shipped_at") != null ? s.get("shipped_at") : firstTime(members, "shipped_at", true);
        Object delivered = s.get("delivered_at") != null ? s.get("delivered_at") : st[0].equals("DELIVERED") ? firstTime(members, "delivered_at", false) : null;
        Map<String, Object> destination;
        try { destination = json.readValue((String) s.get("destination"), new TypeReference<>() {}); } catch (Exception e) { throw new IllegalStateException(e); }
        String tracking = (String) s.get("tracking_number");
        if (tracking == null) tracking = trackingOf(members);
        return new Shipment(id, (String) s.get("method"), (String) s.get("method_name"), (String) s.get("strategy"), ((Number) s.get("fee_twd")).intValue(),
                ((Number) s.get("goods_twd")).longValue(), st[0], st[1], (String) s.get("carrier"), tracking, time(shipped), time(delivered),
                destination, members.stream().map(m -> new ShipmentOrder(m.kind(), m.id(), m.title(), m.status(), m.fulfillment())).toList(),
                ((Number) s.get("version")).intValue(), time(s.get("created_at")));
    }
    private Object firstTime(List<Member> members, String column, boolean min) {
        Object best = null;
        for (var m : members) {
            var t = db.queryForList("SELECT " + column + " FROM " + (m.kind().equals("STOCK") ? "customer_order" : "preorder_order") + " WHERE id=? AND " + column + " IS NOT NULL", Object.class, m.id());
            if (t.isEmpty()) continue;
            var v = t.get(0);
            if (best == null || (min == (((Comparable<Object>) v).compareTo(best) < 0))) best = v;
        }
        return best;
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
