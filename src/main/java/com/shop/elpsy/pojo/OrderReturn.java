package com.shop.elpsy.pojo;
import lombok.Data;
import java.time.LocalDateTime;
@Data
public class OrderReturn {
 private String orderId,requestId,customerId,reason,status,disposition;
 private int expectedOrderVersion;
 private LocalDateTime createdAt,receivedAt,refundedAt,rejectedAt;
}
