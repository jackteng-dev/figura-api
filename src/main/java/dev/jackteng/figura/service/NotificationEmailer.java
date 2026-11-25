package dev.jackteng.figura.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * 通知轉為 SENT 時，依顧客偏好決定是否同時寄 Email。
 * 條件：顧客啟用、Email 已驗證、偏好 email_enabled，且該類別（訂單／商品提醒）開啟。站內通知匣不受偏好影響。
 */
@Service
public class NotificationEmailer {
    private final JdbcTemplate db; private final EmailSender sender; private final ObjectMapper json;
    public NotificationEmailer(JdbcTemplate db, EmailSender sender, ObjectMapper json) { this.db = db; this.sender = sender; this.json = json; }

    @Transactional(propagation = Propagation.MANDATORY)
    public boolean onSent(String recipient, String kind, String payload, String productId) {
        if (!sender.active()) return false;
        boolean product = productId != null;
        var rows = db.queryForList("SELECT c.email FROM customer c JOIN customer_notification_preference n ON n.customer_id=c.id WHERE c.id=? AND c.enabled=TRUE AND c.email IS NOT NULL AND c.email_verified_at IS NOT NULL AND n.email_enabled=TRUE AND "
                + (product ? "n.product_alerts=TRUE" : "n.order_updates=TRUE"), String.class, recipient);
        if (rows.isEmpty()) return false;
        com.fasterxml.jackson.databind.JsonNode p;
        try { p = json.readTree(payload); } catch (Exception e) { p = json.createObjectNode(); }
        String[] text = NoticeText.of(kind, p);
        String link = product ? "/shop/products/" + productId : "/shop/my-orders";
        sender.send(recipient, rows.get(0), "Figura｜" + text[0], link);
        return true;
    }
}
