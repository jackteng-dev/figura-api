package dev.jackteng.figura.service;

import dev.jackteng.figura.dto.OrderCenterPayloads.*;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 統一訂單中心（唯讀）。以一個 UNION 查詢合併本人現貨與預購訂單，在資料庫內完成篩選、排序與分頁，
 * 所以分頁與總數是跨兩種訂單的全域正確結果（不是兩邊各取一頁再拼接）。owner 只來自已驗證的 Principal。
 */
@Service
public class OrderCenterService {
    private static final ZoneOffset TAIPEI = ZoneOffset.ofHours(8);
    private final NamedParameterJdbcTemplate db;
    public OrderCenterService(NamedParameterJdbcTemplate db) { this.db = db; }

    /** 兩種訂單的共同欄位；stage 依原始狀態推導（付款待核對優先於履約進度）。 */
    private static final String UNION = """
        SELECT 'STOCK' kind, o.id, o.status, o.created_at, o.subtotal_twd + o.shipping_twd total_twd, o.expires_at,
               NULL arrival_from, NULL arrival_to, NULL arrival_status, o.fulfillment_status,
               (SELECT COUNT(*) FROM customer_order_line l WHERE l.order_id=o.id) line_count,
               (SELECT COALESCE(SUM(l.quantity),0) FROM customer_order_line l WHERE l.order_id=o.id) quantity,
               (SELECT l.title FROM customer_order_line l WHERE l.order_id=o.id ORDER BY l.product_id, l.sku_id LIMIT 1) title,
               (SELECT l.product_id FROM customer_order_line l WHERE l.order_id=o.id ORDER BY l.product_id, l.sku_id LIMIT 1) product_id,
               EXISTS(SELECT 1 FROM mock_payment p WHERE p.order_id=o.id AND p.status='REVIEW_REQUIRED'
                      AND NOT EXISTS(SELECT 1 FROM mock_refund_event r WHERE r.payment_id=p.id AND r.action='REVIEW_REFUND')) review,
               EXISTS(SELECT 1 FROM mock_order_return t WHERE t.order_id=o.id AND t.status IN ('REQUESTED','RECEIVED')) returning
          FROM customer_order o WHERE o.customer_id=:customer
        UNION ALL
        SELECT 'PREORDER', p.id, p.status, p.created_at, p.subtotal_twd + p.shipping_twd, p.expires_at,
               p.arrival_from, p.arrival_to, p.arrival_status, p.fulfillment_status,
               1, p.quantity, p.product_title,
               (SELECT b.product_id FROM preorder_batch b WHERE b.id=p.batch_id),
               EXISTS(SELECT 1 FROM preorder_mock_payment m LEFT JOIN preorder_refund_event f ON f.payment_id=m.id
                      WHERE m.order_id=p.id AND m.status='REVIEW_REQUIRED' AND f.request_id IS NULL),
               FALSE
          FROM preorder_order p WHERE p.customer_id=:customer
        """;
    private static final String STAGED = """
        SELECT u.*, CASE
            WHEN u.status IN ('PENDING_PAYMENT','PENDING') THEN 'PENDING_PAYMENT'
            WHEN u.status IN ('CANCELLED','EXPIRED') THEN 'CANCELLED'
            WHEN u.status='REFUNDED' THEN 'REFUNDED'
            WHEN u.review THEN 'REVIEW'
            WHEN u.returning THEN 'RETURNING'
            WHEN u.kind='PREORDER' AND u.arrival_status='NOT_ARRIVED' THEN 'WAITING_ARRIVAL'
            WHEN u.fulfillment_status='SHIPPED' THEN 'SHIPPED'
            WHEN u.fulfillment_status='DELIVERED' THEN 'COMPLETED'
            ELSE 'TO_SHIP' END stage
          FROM (""" + UNION + ") u";

    @Transactional(readOnly = true)
    public Page list(String customer, Kind kind, Stage stage, Sort sort, int page, int pageSize) {
        if (page < 1 || pageSize < 1 || pageSize > 50) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "分頁參數不正確");
        var p = new MapSqlParameterSource("customer", customer).addValue("kind", kind.name()).addValue("stage", stage.name())
                .addValue("limit", pageSize).addValue("offset", (long) (page - 1) * pageSize);
        String byKind = " WHERE (:kind='ALL' OR s.kind=:kind)";
        String filtered = byKind + " AND (:stage='ALL' OR s.stage=:stage)";
        String order = switch (sort) {
            case NEWEST -> " ORDER BY s.created_at DESC, s.id DESC";
            case OLDEST -> " ORDER BY s.created_at ASC, s.id ASC";
            case AMOUNT_DESC -> " ORDER BY s.total_twd DESC, s.created_at DESC, s.id DESC";
            case AMOUNT_ASC -> " ORDER BY s.total_twd ASC, s.created_at DESC, s.id DESC";
        };
        String from = " FROM (" + STAGED + ") s";
        long total = db.queryForObject("SELECT COUNT(*)" + from + filtered, p, Long.class);
        var items = db.query("SELECT s.*" + from + filtered + order + " LIMIT :limit OFFSET :offset", p, (r, i) -> new Item(
                r.getString("kind"), r.getString("id"), r.getString("stage"), r.getString("status"), time(r.getObject("created_at", LocalDateTime.class)),
                r.getLong("total_twd"), r.getString("title"), r.getInt("line_count"), r.getInt("quantity"), r.getString("product_id"),
                time(r.getObject("expires_at", LocalDateTime.class)), r.getObject("arrival_from", LocalDate.class), r.getObject("arrival_to", LocalDate.class),
                r.getString("arrival_status"), r.getString("fulfillment_status"), r.getBoolean("review")));
        Map<String, Long> counts = new LinkedHashMap<>();
        for (Stage s : Stage.values()) if (s != Stage.ALL) counts.put(s.name(), 0L);
        db.query("SELECT s.stage, COUNT(*) n" + from + byKind + " GROUP BY s.stage", p, r -> { counts.put(r.getString("stage"), r.getLong("n")); });
        counts.put(Stage.ALL.name(), counts.values().stream().mapToLong(Long::longValue).sum());
        return new Page(items, page, pageSize, total, counts);
    }
    private static java.time.OffsetDateTime time(LocalDateTime t) { return t == null ? null : t.atOffset(TAIPEI); }
}
