package com.shop.elpsy.pojo;
import lombok.Data;
@Data
public class InventoryBalance {
    private String skuId;
    private int quantity, reserved, version;
}
