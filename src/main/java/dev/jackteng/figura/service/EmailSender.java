package dev.jackteng.figura.service;

/**
 * 通知 Email 管道。目前只有 {@link MockEmailSender}（寫入 customer_mock_email，不實際寄出）。
 * 正式寄信（SMTP／SES 等）需要寄件網域與金鑰，列在待啟用清單；接入時另做實作並加上有界 timeout，
 * 不可在持有業務鎖的交易中做網路 I/O（見 docs/NOTIFICATIONS_API.md）。
 */
public interface EmailSender {
    /** 是否有可用的寄送管道（mock 啟用或正式寄信已設定）。 */
    boolean active();
    void send(String customerId, String recipient, String subject, String link);
}
