package dev.jackteng.figura.pojo;
import lombok.Data;
import java.time.LocalDateTime;
@Data
public class CustomerOrder {
 private String id,customerId,requestId,addressId,recipientName,phone,postalCode,city,district,street,status,fulfillmentStatus,shipmentTrackingId;
 private int cartVersion,addressVersion,shippingTwd,version;
 private long subtotalTwd;
 private LocalDateTime createdAt,expiresAt,closedAt,paidAt,shippedAt,deliveredAt;
}
