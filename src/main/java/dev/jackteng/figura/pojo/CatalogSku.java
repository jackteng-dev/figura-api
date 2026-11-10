package dev.jackteng.figura.pojo;
import lombok.Data;
@Data
public class CatalogSku {
    private String id, productId, code, label;
    private int priceTwd;
    private int quantity;
    private boolean enabled;
}
