package dev.jackteng.figura.dto;
import com.fasterxml.jackson.annotation.JsonAnySetter;
import jakarta.validation.constraints.*;
import java.time.OffsetDateTime;
import java.util.List;
public final class PreorderMoneyPayloads {
 private PreorderMoneyPayloads() {}
 public record Refund(@NotBlank @Pattern(regexp=PreorderPayloads.UUID_PATTERN) String requestId,@NotNull @Min(1) Integer expectedOrderVersion,@NotBlank @Size(max=500) String note){
  @JsonAnySetter public void unknown(String key,Object value){throw new IllegalArgumentException("不支援的欄位");}
 }
 public record RefundProgress(String provider,long amountTwd,OffsetDateTime refundedAt) {}
 public record Payment(String id,String orderId,String status,long amountTwd,String currency,String provider,OffsetDateTime createdAt,OffsetDateTime settledAt,RefundProgress refund) {}
 public record Options(boolean enabled,boolean canStart,List<Payment> items) {}
 public record Started(Payment payment,boolean replayed) {}
 public record Receipt(String eventId,String disposition,boolean replayed,Payment payment,PreorderOrderPayloads.View order) {}
 public record Evidence(String eventId,String outcome,String disposition,OffsetDateTime createdAt) {}
 public record Review(String paymentId,long amountTwd,boolean refunded,List<Evidence> evidence) {}
 public record Audit(String requestId,String orderId,String paymentId,int actorId,String actorUsername,String action,String note,long amountTwd,int beforeVersion,int afterVersion,OffsetDateTime createdAt) {}
 public record Detail(PreorderOrderPayloads.View order,Options payments,boolean refundsEnabled,boolean canRefund,List<Review> reviews,List<Audit> refunds,PreorderFulfillmentPayloads.Detail fulfillment) {}
 public record Applied(Audit event,Detail current,boolean replayed) {}
 public record Summary(String id,String batchId,String batchName,String productTitle,String skuLabel,int quantity,long totalTwd,
  String status,int version,OffsetDateTime createdAt,OffsetDateTime paidAt,boolean paymentReviewRequired) {}
}
