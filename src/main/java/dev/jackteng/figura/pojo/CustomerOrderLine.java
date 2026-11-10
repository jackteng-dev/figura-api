package dev.jackteng.figura.pojo;
import lombok.Data;
@Data
public class CustomerOrderLine {
 private String orderId,skuId,productId,title,label;
 private int quantity,unitPriceTwd;
}
