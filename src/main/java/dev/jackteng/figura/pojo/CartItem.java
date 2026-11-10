package dev.jackteng.figura.pojo;
import lombok.Data;
@Data
public class CartItem {
    private String skuId,productId,titleSnapshot,labelSnapshot,title,label,status;
    private int quantity,priceTwd,stock;
    private boolean enabled;
}
