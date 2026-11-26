package dev.jackteng.figura.service;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import java.time.LocalDateTime;
import java.util.UUID;

/** 模擬寄信：MOCK_EMAIL_ENABLED=true 時寫入 customer_mock_email（purpose=NOTIFY），由有權限員工在後台檢視；不寄出。 */
@Service
public class MockEmailSender implements EmailSender {
    private final JdbcTemplate db; private final boolean enabled;
    public MockEmailSender(JdbcTemplate db, @Value("${MOCK_EMAIL_ENABLED:false}") boolean enabled) { this.db = db; this.enabled = enabled; }
    @Override public boolean active() { return enabled; }
    @Override public void send(String customerId, String recipient, String subject, String link) {
        if (!enabled) return;
        db.update("INSERT INTO customer_mock_email(id,customer_id,purpose,recipient,subject,link,expires_at) VALUES(?,?,?,?,?,?,?)",
                UUID.randomUUID().toString(), customerId, "NOTIFY", recipient, subject.length() > 120 ? subject.substring(0, 120) : subject, link, LocalDateTime.now().plusDays(30));
    }
}
