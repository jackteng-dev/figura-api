package dev.jackteng.figura.dto;
import com.fasterxml.jackson.annotation.JsonAnySetter;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.util.List;

public final class CatalogPayloads {
    private CatalogPayloads() {}
    public record Sku(
            @Pattern(regexp="[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}") String id,
            @NotBlank @Pattern(regexp="[A-Za-z0-9_-]{1,64}") String code,
            @NotBlank @Size(max=80) String label,
            @NotNull @Min(1) @Max(99999999) Integer priceTwd, @NotNull Boolean enabled) {
        @JsonAnySetter public void unknown(String name,Object value){throw new IllegalArgumentException("不支援的欄位");}
    }
    public record Write(@NotBlank @Size(max=120) String title,
                        @Size(max=80) String brand,
                        @Size(max=80) String category,
                        @NotBlank @Size(max=8000) String description,
                        @NotNull @Min(0) Integer expectedVersion,
                        @NotNull @Size(min=1,max=50) List<@NotNull @Valid Sku> skus,
                        @Pattern(regexp="[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}") String brandId,
                        @Pattern(regexp="[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}") String categoryId,
                        @Pattern(regexp="[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}") String workId) {
        public Write(String title,String brand,String category,String description,Integer expectedVersion,List<Sku> skus){this(title,brand,category,description,expectedVersion,skus,null,null,null);}
        @com.fasterxml.jackson.annotation.JsonIgnore @AssertTrue(message="請選擇品牌與分類主檔")
        public boolean isAssociationValid(){return brandId!=null||categoryId!=null ? brandId!=null&&categoryId!=null : brand!=null&&!brand.isBlank()&&category!=null&&!category.isBlank();}
        @JsonAnySetter public void unknown(String name,Object value){throw new IllegalArgumentException("不支援的欄位");}
    }
    public record Publication(@NotNull @Min(0) Integer expectedVersion,@NotNull Boolean published) {
        @JsonAnySetter public void unknown(String name,Object value){throw new IllegalArgumentException("不支援的欄位");}
    }
    public record AdminView(String id,String title,String brand,String category,String description,
                            String status,int version,List<Sku> skus,ImagePayloads.Image image,String brandId,String categoryId,String workId,String work) {}
    public record PublicSku(String id,String label,int priceTwd,boolean available) {}
    public record PublicView(String id,String title,String brand,String category,String description,List<PublicSku> skus,ImagePayloads.Image image,String work) {}
    public record Page<T>(List<T> items,int page,int pageSize,long total) {}
}
