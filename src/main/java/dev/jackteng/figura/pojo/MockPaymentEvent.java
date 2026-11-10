package dev.jackteng.figura.pojo;
import lombok.Data;
@Data
public class MockPaymentEvent {
 private java.time.LocalDateTime createdAt;
 private String eventId,paymentId,fingerprint,outcome,disposition;
}
