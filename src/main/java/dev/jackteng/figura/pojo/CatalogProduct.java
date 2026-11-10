package dev.jackteng.figura.pojo;
import lombok.Data;
import java.time.LocalDateTime;
@Data
public class CatalogProduct {
    private String id, title, brand, category, description, status, brandId, categoryId, workId;
    private int version;
    private LocalDateTime createdAt, updatedAt;
}
