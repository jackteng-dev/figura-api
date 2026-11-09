package com.shop.elpsy.pojo;
import lombok.Data;
import java.time.LocalDateTime;
@Data
public class InventoryAdjustment {
    private String requestId, skuId, actorUsername, reason, note;
    private int actorId, delta, beforeQuantity, afterQuantity, beforeVersion, afterVersion;
    private LocalDateTime createdAt;
}
