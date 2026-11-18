package dev.jackteng.figura.dto;

import com.fasterxml.jackson.annotation.JsonAnySetter;
import jakarta.validation.constraints.*;
import java.util.List;

public final class ShoppingPayloads {
    private ShoppingPayloads() {}
    public record CartWrite(@NotNull @Min(0) Integer expectedVersion,
                            @NotNull @Min(1) @Max(99) Integer quantity) {
        @JsonAnySetter public void unknown(String name,Object value){throw new IllegalArgumentException("不支援的欄位");}
    }
    public record CartLine(String skuId,String productId,String title,String label,int quantity,
                           Integer unitPriceTwd,Long lineTotalTwd,String issue) {}
    /** 購物車預購項目（以批次為鍵，同 SKU 不同批次分開）。issue：UNAVAILABLE／CLOSED／EXPIRED／INSUFFICIENT_QUOTA。 */
    public record PreorderLine(String batchId,String productId,String skuId,String title,String batchName,String label,int quantity,
                               int unitPriceTwd,long lineTotalTwd,java.time.OffsetDateTime deadlineAt,java.time.LocalDate arrivalFrom,
                               java.time.LocalDate arrivalTo,int availableQuota,String issue,String paymentMode,Integer depositTwd) {}
    /**
     * items／subtotalTwd／ready 只計現貨（與舊版網站、App 相容，舊的 POST /customer/orders 仍只結帳現貨）。
     * preorders、preorderSubtotalTwd、totalTwd、checkoutReady 是統一結帳用的新欄位。
     */
    public record CartView(int version,List<CartLine> items,long subtotalTwd,boolean ready,
                           List<PreorderLine> preorders,long preorderSubtotalTwd,long totalTwd,boolean checkoutReady) {}
    public record PreorderCartWrite(@NotNull @Min(0) Integer expectedVersion,
                                    @NotNull @Min(1) @Max(100) Integer quantity) {
        @JsonAnySetter public void unknown(String name,Object value){throw new IllegalArgumentException("不支援的欄位");}
    }
    public record AddressWrite(@NotNull @Min(0) Integer expectedVersion,
            @NotBlank @Size(max=80) String recipientName,
            @NotBlank @Pattern(regexp="\\+?[0-9]{8,15}") String phone,
            @NotBlank @Pattern(regexp="[0-9]{3}([0-9]{2}|[0-9]{3})?") String postalCode,
            @NotBlank @Size(max=40) String city,
            @NotBlank @Size(max=40) String district,
            @NotBlank @Size(max=200) String street) {
        @JsonAnySetter public void unknown(String name,Object value){throw new IllegalArgumentException("不支援的欄位");}
    }
    public record AddressView(String id,int version,String recipientName,String phone,String postalCode,
                              String city,String district,String street) {}
}
