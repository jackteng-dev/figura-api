package com.shop.elpsy.dto;
import com.shop.elpsy.pojo.RefundEvent;
import java.time.LocalDateTime;
import java.util.List;
public final class RefundPayloads {
 private RefundPayloads(){}
 public record Progress(String status,String provider,long amountTwd,LocalDateTime refundedAt){}
 public record Evidence(String eventId,String outcome,String disposition,LocalDateTime receivedAt){}
 public record Review(String paymentId,long amountTwd,String currency,LocalDateTime detectedAt,boolean resolved,List<Evidence> notifications){}
 public record Audit(String requestId,String orderId,String paymentId,int actorId,String actorUsername,String action,String note,long amountTwd,int beforeVersion,int afterVersion,LocalDateTime createdAt){
  public static Audit from(RefundEvent e){return new Audit(e.getRequestId(),e.getOrderId(),e.getPaymentId(),e.getActorId(),e.getActorUsername(),e.getAction(),e.getNote(),e.getAmountTwd(),e.getBeforeVersion(),e.getAfterVersion(),e.getCreatedAt());}
 }
 public record Detail(boolean mockEnabled,boolean canRefund,List<Review> reviews,List<Audit> events){}
 public record Applied(Audit event,OrderPayloads.View order,Detail current,boolean replayed){}
}
