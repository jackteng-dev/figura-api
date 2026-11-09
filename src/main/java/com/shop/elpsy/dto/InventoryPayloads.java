package com.shop.elpsy.dto;
import com.fasterxml.jackson.annotation.JsonAnySetter;
import com.shop.elpsy.pojo.InventoryAdjustment;
import jakarta.validation.constraints.*;
import java.time.LocalDateTime;

public final class InventoryPayloads {
    private InventoryPayloads() {}
    public record Adjust(
            @NotBlank @Pattern(regexp="[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}") String requestId,
            @NotNull @Min(0) Integer expectedVersion,
            @NotNull @Min(-1000000) @Max(1000000) Integer delta,
            @NotBlank @Pattern(regexp="RESTOCK|CORRECTION|DAMAGE") String reason,
            @NotBlank @Size(max=500) String note) {
        @AssertTrue(message="調整數量不可為零，且需符合異動原因")
        public boolean isReasonValid(){return delta==null || reason==null || (delta!=0 && (reason.equals("CORRECTION") || (reason.equals("RESTOCK")&&delta>0) || (reason.equals("DAMAGE")&&delta<0)));}
        @JsonAnySetter public void unknown(String name,Object value){throw new IllegalArgumentException("不支援的欄位");}
    }
    public record Balance(String skuId,int quantity,int version,int reserved,int available) {}
    public record Adjustment(String requestId,String skuId,int actorId,String actorUsername,int delta,String reason,String note,int beforeQuantity,int afterQuantity,int beforeVersion,int afterVersion,LocalDateTime createdAt) {
        public static Adjustment from(InventoryAdjustment a){return new Adjustment(a.getRequestId(),a.getSkuId(),a.getActorId(),a.getActorUsername(),a.getDelta(),a.getReason(),a.getNote(),a.getBeforeQuantity(),a.getAfterQuantity(),a.getBeforeVersion(),a.getAfterVersion(),a.getCreatedAt());}
    }
    public record Applied(Adjustment adjustment,boolean replayed) {}
}
