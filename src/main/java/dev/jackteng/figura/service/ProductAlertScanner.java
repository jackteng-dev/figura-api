package dev.jackteng.figura.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.jackteng.figura.dto.EngagementPayloads.ScanResult;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.*;

/**
 * 商品提醒掃描：只看「有人訂閱」的商品，與上次觀察比較，只在狀態轉換時寫 notification_outbox（product parent）。
 * - 第一次觀察只建立基準，不補發歷史，部署後不會一次灌出大量通知。
 * - 每個商品一個短交易，只鎖自己的 product_alert_state 列；讀庫存／批次不加鎖，不與下單、付款等業務鎖互等。
 * - 每位訂閱者每次轉換一筆，source_id 由（種類、商品、轉換鍵、顧客）決定，重跑或併發都不會重複（uq_notification_source）。
 * - 掃描本身不派送；派送沿用既有 outbox worker／mock 流程。
 */
@Service
public class ProductAlertScanner {
    static final int CLOSING_HOURS = 48;
    private final JdbcTemplate db; private final ObjectMapper json; private final TransactionTemplate tx;

    public ProductAlertScanner(JdbcTemplate db, ObjectMapper json, PlatformTransactionManager tm) {
        this.db = db; this.json = json;
        this.tx = new TransactionTemplate(tm); this.tx.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
    }

    /** 掃描最久未掃的 limit 個已訂閱商品。 */
    public ScanResult scan(int limit) {
        var ids = db.queryForList("SELECT a.product_id FROM customer_product_alert a LEFT JOIN product_alert_state s ON s.product_id=a.product_id GROUP BY a.product_id"
                + " ORDER BY MIN(COALESCE(s.scanned_at,TIMESTAMP('1970-01-01'))),a.product_id LIMIT ?", String.class, Math.max(1, Math.min(limit, 500)));
        int events = 0;
        for (String id : ids) { Integer n = tx.execute(s -> scanProduct(id)); events += n == null ? 0 : n; }
        return new ScanResult(ids.size(), events);
    }

    /** 單一商品掃描（自己的短交易）；後台手動或測試用。 */
    public int scanOne(String productId) { Integer n = tx.execute(s -> scanProduct(productId)); return n == null ? 0 : n; }

    private record Now(boolean published, String title, boolean inStock, String openBatch, LocalDateTime deadline, boolean closing, String releaseKey) {}

    private Now observe(String product) {
        var p = db.queryForList("SELECT p.status,p.title," + CatalogService.IN_STOCK + " in_stock,"
                + "CASE WHEN p.release_date IS NOT NULL AND p.release_date<=CURRENT_DATE THEN DATE_FORMAT(p.release_date,'%Y-%m-%d')"
                + " WHEN p.release_date IS NULL AND p.release_month IS NOT NULL AND p.release_month<=DATE_FORMAT(CURRENT_DATE,'%Y-%m') THEN p.release_month END release_key"
                + " FROM catalog_product p WHERE p.id=?", product).get(0);
        var b = db.queryForList("SELECT b.id,b.deadline_at,b.deadline_at<=CURRENT_TIMESTAMP(6)+INTERVAL " + CLOSING_HOURS + " HOUR closing FROM preorder_batch b"
                + " WHERE b.product_id=? AND " + CatalogService.ORDERABLE_BATCH + " ORDER BY b.deadline_at,b.id LIMIT 1", product);
        String batch = b.isEmpty() ? null : (String) b.get(0).get("id");
        LocalDateTime deadline = b.isEmpty() ? null : toTime(b.get(0).get("deadline_at"));
        boolean closing = !b.isEmpty() && truthy(b.get(0).get("closing"));
        return new Now("PUBLISHED".equals(p.get("status")), (String) p.get("title"), truthy(p.get("in_stock")), batch, deadline, closing, (String) p.get("release_key"));
    }

    int scanProduct(String product) {
        var state = db.queryForList("SELECT in_stock,restock_seq,open_batch_id,closing_batch_id,released_key FROM product_alert_state WHERE product_id=? FOR UPDATE", product);
        var now = observe(product);
        if (state.isEmpty()) {
            try {
                db.update("INSERT INTO product_alert_state(product_id,in_stock,open_batch_id,closing_batch_id,released_key) VALUES(?,?,?,?,?)",
                        product, now.inStock(), now.openBatch(), now.closing() ? now.openBatch() : null, now.releaseKey());
            } catch (DuplicateKeyException race) { /* 另一個掃描已建立基準 */ }
            return 0;
        }
        var s = state.get(0);
        boolean wasInStock = truthy(s.get("in_stock"));
        int seq = ((Number) s.get("restock_seq")).intValue();
        String wasOpen = (String) s.get("open_batch_id"), wasClosing = (String) s.get("closing_batch_id"), wasReleased = (String) s.get("released_key");
        if (!now.published()) {   // 未上架只更新觀察時間；重新上架後依當下狀態比較（下架期間的變化不補發）
            db.update("UPDATE product_alert_state SET scanned_at=CURRENT_TIMESTAMP(6) WHERE product_id=?", product);
            return 0;
        }
        int events = 0;
        if (!wasInStock && now.inStock()) { seq++; events += enqueue(product, "RESTOCK", "restock-" + seq, now); }
        if (now.openBatch() != null && !now.openBatch().equals(wasOpen)) events += enqueue(product, "PREORDER_OPEN", now.openBatch(), now);
        if (now.closing() && !now.openBatch().equals(wasClosing)) events += enqueue(product, "PREORDER_CLOSING", now.openBatch(), now);
        if (now.releaseKey() != null && !now.releaseKey().equals(wasReleased)) events += enqueue(product, "RELEASE", now.releaseKey(), now);
        db.update("UPDATE product_alert_state SET in_stock=?,restock_seq=?,open_batch_id=?,closing_batch_id=?,released_key=?,scanned_at=CURRENT_TIMESTAMP(6) WHERE product_id=?",
                now.inStock(), seq, now.openBatch(), now.closing() ? now.openBatch() : wasClosing, now.releaseKey() != null ? now.releaseKey() : wasReleased, product);
        return events;
    }

    private int enqueue(String product, String alert, String key, Now now) {
        String kind = "PRODUCT_" + alert;
        var payload = new LinkedHashMap<String, Object>();
        payload.put("productId", product); payload.put("eventKind", kind); payload.put("title", now.title());
        payload.put("availability", now.inStock() ? "IN_STOCK" : now.openBatch() != null ? "PREORDER" : "SOLD_OUT");
        if (alert.startsWith("PREORDER") && now.openBatch() != null) {
            payload.put("batchId", now.openBatch());
            if (now.deadline() != null) payload.put("deadlineAt", now.deadline().toString());
        }
        if (alert.equals("RELEASE")) payload.put("releaseKey", key);
        String body; try { body = json.writeValueAsString(payload); } catch (Exception e) { throw new IllegalStateException(e); }
        int n = 0;
        for (String customer : db.queryForList("SELECT a.customer_id FROM customer_product_alert a JOIN customer c ON c.id=a.customer_id WHERE a.product_id=? AND a.kind=? AND c.enabled=TRUE ORDER BY a.customer_id",
                String.class, product, alert)) {
            String source = UUID.nameUUIDFromBytes((kind + "|" + product + "|" + key + "|" + customer).getBytes(StandardCharsets.UTF_8)).toString();
            n += db.update("INSERT INTO notification_outbox(id,event_kind,source_id,product_id,recipient_ref,payload) VALUES(?,?,?,?,?,?) ON DUPLICATE KEY UPDATE id=id",
                    UUID.randomUUID().toString(), kind, source, product, customer, body) == 1 ? 1 : 0;
        }
        return n;
    }
    private static boolean truthy(Object v) { return v instanceof Boolean b ? b : v instanceof Number n && n.intValue() != 0; }
    private static LocalDateTime toTime(Object v) { return v instanceof LocalDateTime t ? t : v instanceof java.sql.Timestamp ts ? ts.toLocalDateTime() : null; }
}
