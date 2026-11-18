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
    /** 兩種訂單的共同欄位。現貨明細以「本人訂單」範圍內的彙總 join 取得（不對每筆訂單跑多個子查詢）。 */
    private static final String UNION = """
        SELECT 'STOCK' kind, o.id, o.status, o.created_at, o.subtotal_twd + o.shipping_twd total_twd, o.expires_at,
               NULL arrival_from, NULL arrival_to, NULL arrival_status, o.fulfillment_status,
               COALESCE(a.line_count,0) line_count, COALESCE(a.quantity,0) quantity, fl.title, fl.product_id,
               EXISTS(SELECT 1 FROM mock_payment p WHERE p.order_id=o.id AND p.status='REVIEW_REQUIRED'
                      AND NOT EXISTS(SELECT 1 FROM mock_refund_event r WHERE r.payment_id=p.id AND r.action='REVIEW_REFUND')) review,
               EXISTS(SELECT 1 FROM mock_order_return t WHERE t.order_id=o.id AND t.status IN ('REQUESTED','RECEIVED')) returning,
               NULL balance_status, NULL balance_due_at
          FROM customer_order o
          LEFT JOIN (SELECT l.order_id, COUNT(*) line_count, SUM(l.quantity) quantity, MIN(CONCAT(l.product_id,'|',l.sku_id)) first_key
                       FROM customer_order_line l JOIN customer_order lo ON lo.id=l.order_id AND lo.customer_id=:customer
                      GROUP BY l.order_id) a ON a.order_id=o.id
          LEFT JOIN customer_order_line fl ON fl.order_id=o.id AND CONCAT(fl.product_id,'|',fl.sku_id)=a.first_key
         WHERE o.customer_id=:customer
        UNION ALL
        SELECT 'PREORDER', p.id, p.status, p.created_at, p.subtotal_twd + p.shipping_twd, p.expires_at,
               p.arrival_from, p.arrival_to, p.arrival_status, p.fulfillment_status,
               1, p.quantity, p.product_title, b.product_id,
               EXISTS(SELECT 1 FROM preorder_mock_payment m LEFT JOIN preorder_refund_event f ON f.payment_id=m.id
                      WHERE m.order_id=p.id AND m.status='REVIEW_REQUIRED' AND f.request_id IS NULL),
               FALSE, p.balance_status, p.balance_due_at
          FROM preorder_order p LEFT JOIN preorder_batch b ON b.id=p.batch_id
         WHERE p.customer_id=:customer
        """;
    /**
     * 未處理的付款核對最優先：失敗後或取消／逾時後才晚到的成功通知，原始狀態不變但需要人工處理，
     * 必須出現在「付款待核對」並計入筆數；核對完成（退款事件）後回到原本階段。
     */
    private static final String STAGED = """
        SELECT u.*, CASE
            WHEN u.review THEN 'REVIEW'
            WHEN u.status IN ('PENDING_PAYMENT','PENDING') THEN 'PENDING_PAYMENT'
            WHEN u.status='PAID' AND u.balance_status='DUE' THEN 'PENDING_PAYMENT'
            WHEN u.status IN ('CANCELLED','EXPIRED') THEN 'CANCELLED'
            WHEN u.status='REFUNDED' THEN 'REFUNDED'
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
            case NEWEST -> " ORDER BY s.created_at DESC, s.kind, s.id DESC";
            case OLDEST -> " ORDER BY s.created_at ASC, s.kind DESC, s.id ASC";
            case AMOUNT_DESC -> " ORDER BY s.total_twd DESC, s.created_at DESC, s.kind, s.id DESC";
            case AMOUNT_ASC -> " ORDER BY s.total_twd ASC, s.created_at DESC, s.kind, s.id DESC";
        };
        String from = " FROM (" + STAGED + ") s";
        long total = db.queryForObject("SELECT COUNT(*)" + from + filtered, p, Long.class);
        var items = db.query("SELECT s.*" + from + filtered + order + " LIMIT :limit OFFSET :offset", p, (r, i) -> new Item(
                r.getString("kind"), r.getString("id"), r.getString("stage"), r.getString("status"), time(r.getObject("created_at", LocalDateTime.class)),
                r.getLong("total_twd"), r.getString("title"), r.getInt("line_count"), r.getInt("quantity"), r.getString("product_id"),
                time(r.getObject("expires_at", LocalDateTime.class)), r.getObject("arrival_from", LocalDate.class), r.getObject("arrival_to", LocalDate.class),
                r.getString("arrival_status"), r.getString("fulfillment_status"), r.getBoolean("review"),
                r.getString("balance_status"), time(r.getObject("balance_due_at", LocalDateTime.class))));
        Map<String, Long> counts = new LinkedHashMap<>();
        for (Stage s : Stage.values()) if (s != Stage.ALL) counts.put(s.name(), 0L);
        db.query("SELECT s.stage, COUNT(*) n" + from + byKind + " GROUP BY s.stage", p, r -> { counts.put(r.getString("stage"), r.getLong("n")); });
        counts.put(Stage.ALL.name(), counts.values().stream().mapToLong(Long::longValue).sum());
        return new Page(items, page, pageSize, total, counts);
    }
    private static java.time.OffsetDateTime time(LocalDateTime t) { return t == null ? null : t.atOffset(TAIPEI); }
}
