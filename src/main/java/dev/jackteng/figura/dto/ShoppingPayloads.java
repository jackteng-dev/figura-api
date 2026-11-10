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
    public record CartView(int version,List<CartLine> items,long subtotalTwd,boolean ready) {}
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
