package dev.jackteng.figura.pojo;
import lombok.Data;
import java.time.LocalDateTime;
@Data
public class MockPayment {
 private String id,orderId,customerId,requestId,transactionId,currency,status;
 private int expectedOrderVersion;
 private long amountTwd;
 private LocalDateTime createdAt,settledAt;
}
