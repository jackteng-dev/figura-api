package dev.jackteng.figura.pojo;
import lombok.Data;
import java.time.LocalDateTime;
@Data
public class CatalogProduct {
    private String id, title, brand, category, description, status, brandId, categoryId, workId;
    private int version;
    // Commerce V2 規格（V25）：NULL＝未提供
    private String characterName, manufacturer, scale, dimensions, materials, productType, edition, accessories, releaseMonth, specNote, contentOrigin, conceptMaterials, searchText;
    private Integer heightMm;
    private Boolean isLimited;
    private java.time.LocalDate releaseDate;
    private LocalDateTime createdAt, updatedAt;
}
