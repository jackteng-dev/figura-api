package dev.jackteng.figura.dto;
import com.fasterxml.jackson.annotation.JsonAnySetter;
import jakarta.validation.constraints.*;
import java.time.LocalDateTime;
public final class FulfillmentPayloads {
 private FulfillmentPayloads() {}
 public record Change(@NotBlank @Pattern(regexp=PaymentPayloads.UUID) String requestId,
  @NotNull @Min(1) Integer expectedOrderVersion,@NotBlank @Size(max=500) String note){
  @JsonAnySetter public void unknown(String name,Object value){throw new IllegalArgumentException("不支援的欄位");}
 }
 // Customer-safe projection: internal notes and staff identity are only in the authorized audit API.
 public record Progress(String status,String provider,String trackingId,LocalDateTime shippedAt,LocalDateTime deliveredAt) {}
 public record Summary(String id,String status,int version,long totalTwd,String fulfillmentStatus,LocalDateTime createdAt,LocalDateTime paidAt) {}
 public record Detail(OrderPayloads.View order,boolean mockEnabled,boolean canShip,boolean canDeliver) {}
 public record Audit(String requestId,String orderId,int actorId,String actorUsername,String action,String note,
  int beforeVersion,int afterVersion,String beforeStatus,String afterStatus,String trackingId,LocalDateTime createdAt) {
  public static Audit from(dev.jackteng.figura.pojo.FulfillmentEvent e){return new Audit(e.getRequestId(),e.getOrderId(),e.getActorId(),e.getActorUsername(),e.getAction(),e.getNote(),e.getBeforeVersion(),e.getAfterVersion(),e.getBeforeStatus(),e.getAfterStatus(),e.getTrackingId(),e.getCreatedAt());}
 }
 public record Applied(Audit event,Detail current,boolean replayed) {}
}
