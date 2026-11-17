package dev.jackteng.figura.service;

import dev.jackteng.figura.dto.CustomerPayloads;
import dev.jackteng.figura.mapper.CustomerMapper;
import dev.jackteng.figura.pojo.Customer;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.*;

/**
 * 信箱驗證與忘記密碼。
 * - 一次性 token：32 bytes 隨機值，連結帶原文，資料庫只存 SHA-256；用過或逾時即失效，同目的新 token 會讓舊 token 失效。
 * - 寄送：目前只有模擬寄信（MOCK_EMAIL_ENABLED=true 時寫入 customer_mock_email，由有通知權限的員工在後台檢視），不實際寄出。
 *   接上真實寄信服務時只需替換 deliver()。
 * - 忘記密碼不揭露帳號是否存在：無論信箱是否註冊都回相同結果。
 */
@Service
public class CustomerEmailService {
    public static final String VERIFY = "VERIFY", RESET = "RESET";
    static final Duration VERIFY_TTL = Duration.ofHours(24), RESET_TTL = Duration.ofMinutes(30);
    private final JdbcTemplate db;
    private final CustomerMapper customers;
    private final BCryptPasswordEncoder passwords;
    private final StringRedisTemplate redis;
    private final CustomerSessionService sessions;
    private final boolean mockEnabled;
    private final SecureRandom random = new SecureRandom();

    public CustomerEmailService(JdbcTemplate db, CustomerMapper customers, BCryptPasswordEncoder passwords, StringRedisTemplate redis,
                                CustomerSessionService sessions, @Value("${MOCK_EMAIL_ENABLED:false}") boolean mockEnabled) {
        this.db = db; this.customers = customers; this.passwords = passwords; this.redis = redis; this.sessions = sessions; this.mockEnabled = mockEnabled;
    }

    /** 註冊後或本人要求重寄時，寄出信箱驗證信。 */
    @Transactional
    public void sendVerification(Customer customer) {
        if (customer.getEmail() == null || customer.getEmailVerifiedAt() != null) return;
        String token = issue(customer, VERIFY, VERIFY_TTL);
        deliver(customer, VERIFY, "Figura｜請驗證你的 Email", "/shop/verify-email#token=" + token, VERIFY_TTL);
    }

    /** 已登入顧客重寄驗證信；每 60 秒最多一次。 */
    @Transactional
    public void resendVerification(String customerId) {
        var customer = customers.byId(customerId);
        if (customer == null || !customer.isEnabled()) throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "顧客登入狀態無效");
        if (customer.getEmail() == null) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "帳號尚未設定 Email");
        if (customer.getEmailVerifiedAt() != null) throw new ResponseStatusException(HttpStatus.CONFLICT, "Email 已完成驗證");
        if (!throttle("shop:customer:email:resend:" + customerId, 1, Duration.ofSeconds(60)))
            throw new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS, "請稍候 1 分鐘再重寄");
        sendVerification(customer);
    }

    @Transactional
    public CustomerPayloads.View verify(String token) {
        var row = consume(token, VERIFY);
        var customer = customers.lockById(row.customerId());
        if (customer == null || !customer.isEnabled() || !row.email().equals(customer.getEmail()))
            throw invalidLink();
        customers.markEmailVerified(customer.getId(), row.email());
        return CustomerPayloads.View.from(customers.byId(customer.getId()));
    }

    /** 忘記密碼：同一信箱每小時最多 3 封；不論信箱是否存在都靜默成功。 */
    @Transactional
    public void forgot(String rawEmail) {
        String email = CustomerPayloads.normalizeEmail(rawEmail);
        if (email == null) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Email 格式不正確");
        if (!throttle("shop:customer:email:forgot:" + email, 3, Duration.ofHours(1))) return;
        var customer = customers.byEmail(email);
        if (customer == null || !customer.isEnabled()) return;
        String token = issue(customer, RESET, RESET_TTL);
        deliver(customer, RESET, "Figura｜重設密碼", "/shop/reset-password#token=" + token, RESET_TTL);
    }

    /** 重設密碼：token 一次性；成功後撤銷此顧客所有登入（網站與 App）。 */
    @Transactional
    public void reset(String token, String password) {
        var row = consume(token, RESET);
        var customer = customers.lockById(row.customerId());
        if (customer == null || !customer.isEnabled() || !row.email().equals(customer.getEmail())) throw invalidLink();
        customers.updatePassword(customer.getId(), passwords.encode(password));
        // 其他尚未使用的重設連結一併作廢
        db.update("UPDATE customer_email_token SET used_at=CURRENT_TIMESTAMP(6) WHERE customer_id=? AND purpose=? AND used_at IS NULL", customer.getId(), RESET);
        sessions.revokeAll(customer.getId());
    }

    public record MockEmail(String id, String customerId, String purpose, String recipient, String subject, String link,
                            LocalDateTime expiresAt, LocalDateTime createdAt) {}
    public record MockEmailPage(List<MockEmail> items, int page, int pageSize, long total) {}

    @Transactional(readOnly = true)
    public MockEmailPage mockEmails(int page, int pageSize) {
        if (page < 1 || pageSize < 1 || pageSize > 50) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "分頁參數不正確");
        long total = Objects.requireNonNullElse(db.queryForObject("SELECT COUNT(*) FROM customer_mock_email", Long.class), 0L);
        var items = db.query("SELECT * FROM customer_mock_email ORDER BY created_at DESC, id LIMIT ? OFFSET ?",
                (rs, i) -> new MockEmail(rs.getString("id"), rs.getString("customer_id"), rs.getString("purpose"), rs.getString("recipient"),
                        rs.getString("subject"), rs.getString("link"), rs.getObject("expires_at", LocalDateTime.class), rs.getObject("created_at", LocalDateTime.class)),
                pageSize, (long) (page - 1) * pageSize);
        return new MockEmailPage(items, page, pageSize, total);
    }

    // ---- 內部 ----
    private record TokenRow(String id, String customerId, String email) {}

    private String issue(Customer customer, String purpose, Duration ttl) {
        // 同目的的舊連結作廢，只保留最新一封有效
        db.update("UPDATE customer_email_token SET used_at=CURRENT_TIMESTAMP(6) WHERE customer_id=? AND purpose=? AND used_at IS NULL", customer.getId(), purpose);
        byte[] raw = new byte[32]; random.nextBytes(raw);
        String token = Base64.getUrlEncoder().withoutPadding().encodeToString(raw);
        db.update("INSERT INTO customer_email_token(id,customer_id,purpose,token_hash,email,expires_at) VALUES(?,?,?,?,?,?)",
                UUID.randomUUID().toString(), customer.getId(), purpose, sha256(token), customer.getEmail(), LocalDateTime.now().plus(ttl));
        return token;
    }

    private TokenRow consume(String token, String purpose) {
        if (token == null || !token.matches("[A-Za-z0-9_-]{43}")) throw invalidLink();
        var rows = db.query("SELECT id,customer_id,email,expires_at,used_at FROM customer_email_token WHERE token_hash=? AND purpose=? FOR UPDATE",
                (rs, i) -> new Object[]{new TokenRow(rs.getString("id"), rs.getString("customer_id"), rs.getString("email")),
                        rs.getObject("expires_at", LocalDateTime.class), rs.getObject("used_at", LocalDateTime.class)},
                sha256(token), purpose);
        if (rows.isEmpty()) throw invalidLink();
        var r = rows.get(0);
        if (r[2] != null || ((LocalDateTime) r[1]).isBefore(LocalDateTime.now())) throw invalidLink();
        var row = (TokenRow) r[0];
        db.update("UPDATE customer_email_token SET used_at=CURRENT_TIMESTAMP(6) WHERE id=? AND used_at IS NULL", row.id());
        return row;
    }

    private void deliver(Customer customer, String purpose, String subject, String link, Duration ttl) {
        if (!mockEnabled) return; // 尚未接真實寄信服務：不寄出（連結不外流）
        db.update("INSERT INTO customer_mock_email(id,customer_id,purpose,recipient,subject,link,expires_at) VALUES(?,?,?,?,?,?,?)",
                UUID.randomUUID().toString(), customer.getId(), purpose, customer.getEmail(), subject, link, LocalDateTime.now().plus(ttl));
    }

    private boolean throttle(String key, int max, Duration window) {
        Long n = redis.opsForValue().increment(key);
        if (n != null && n == 1) redis.expire(key, window);
        return n != null && n <= max;
    }

    private static String sha256(String value) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8))); }
        catch (Exception e) { throw new IllegalStateException(e); }
    }
    private static ResponseStatusException invalidLink() { return new ResponseStatusException(HttpStatus.BAD_REQUEST, "連結無效或已過期，請重新申請"); }
}
