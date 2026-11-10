package dev.jackteng.figura.pojo;
import lombok.Data;
import java.time.LocalDateTime;
@Data
public class RefundEvent {
 private String requestId,orderId,paymentId,actorUsername,action,note;
 private int actorId,beforeVersion,afterVersion;
 private long amountTwd;
 private LocalDateTime createdAt;
}
