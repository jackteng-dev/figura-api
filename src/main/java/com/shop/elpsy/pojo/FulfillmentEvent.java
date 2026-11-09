package com.shop.elpsy.pojo;
import lombok.Data;
import java.time.LocalDateTime;
@Data
public class FulfillmentEvent {
 private String requestId,orderId,actorUsername,action,note,beforeStatus,afterStatus,trackingId;
 private int actorId,beforeVersion,afterVersion;
 private LocalDateTime createdAt;
}
