package com.shop.elpsy.pojo;
import lombok.Data;
import java.time.LocalDateTime;
@Data
public class ReturnEvent {
 private String requestId,orderId,actorUsername,action,note,beforeStatus,afterStatus,disposition;
 private int actorId,beforeVersion,afterVersion;
 private LocalDateTime createdAt;
}
