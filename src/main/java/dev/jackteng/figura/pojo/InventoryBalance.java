package dev.jackteng.figura.pojo;
import lombok.Data;
@Data
public class InventoryBalance {
    private String skuId;
    private int quantity, reserved, version;
}
