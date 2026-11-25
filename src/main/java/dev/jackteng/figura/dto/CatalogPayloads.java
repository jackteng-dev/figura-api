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
                        @Pattern(regexp="[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}") String workId,
                        // Commerce V2：省略＝不變更既有規格（舊客戶端相容）；提供＝整組取代
                        @Valid Specs specs) {
        public Write(String title,String brand,String category,String description,Integer expectedVersion,List<Sku> skus){this(title,brand,category,description,expectedVersion,skus,null,null,null,null);}
        public Write(String title,String brand,String category,String description,Integer expectedVersion,List<Sku> skus,String brandId,String categoryId,String workId){this(title,brand,category,description,expectedVersion,skus,brandId,categoryId,workId,null);}
        @com.fasterxml.jackson.annotation.JsonIgnore @AssertTrue(message="請選擇品牌與分類主檔")
        public boolean isAssociationValid(){return brandId!=null||categoryId!=null ? brandId!=null&&categoryId!=null : brand!=null&&!brand.isBlank()&&category!=null&&!category.isBlank();}
        @JsonAnySetter public void unknown(String name,Object value){throw new IllegalArgumentException("不支援的欄位");}
    }
    /**
     * 公仔規格（全部可省略＝未提供，不可杜撰）。代碼白名單見 {@link #SCALES} 等常數；materials／conceptMaterials 用代碼。
     * contentOrigin：REAL 真實商品；AI_CONCEPT AI 虛構展示商品（conceptMaterials 只是設定，不是製造規格）。
     */
    public record Specs(@Size(max=120) String characterName, @Size(max=80) String manufacturer, @Size(max=16) String scale,
                        @Min(1) @Max(5000) Integer heightMm, @Size(max=160) String dimensions, @Size(max=8) List<@NotBlank @Size(max=16) String> materials,
                        @Size(max=24) String productType, @Size(max=16) String edition, Boolean limited,
                        @Size(max=30) List<@NotNull @Size(max=80) String> accessories, java.time.LocalDate releaseDate,
                        @Pattern(regexp="20[0-9]{2}-(0[1-9]|1[0-2])") String releaseMonth, @Size(max=2000) String specNote,
                        @Pattern(regexp="REAL|AI_CONCEPT") String contentOrigin, @Size(max=8) List<@NotBlank @Size(max=16) String> conceptMaterials) {
        @JsonAnySetter public void unknown(String name,Object value){throw new IllegalArgumentException("不支援的欄位");}
    }
    public static final List<String> SCALES=List.of("1/1","1/2","1/3","1/4","1/5","1/6","1/7","1/8","1/10","1/12","1/20","1/35","1/60","1/100","1/144","NON_SCALE");
    public static final List<String> MATERIALS=List.of("PVC","ABS","RESIN","POLYSTONE","METAL","FABRIC","OTHER");
    public static final List<String> PRODUCT_TYPES=List.of("PREPAINTED","ACTION_FIGURE","MODEL_KIT","GARAGE_KIT","PRIZE","BLIND_BOX","STATUE","PLUSH","OTHER");
    public static final List<String> EDITIONS=List.of("STANDARD","LIMITED","BONUS","DELUXE");
    /** 開放中的預購批次摘要（預購開始／截止沿用 preorder_batch，不在商品重複存放）。 */
    public record PreorderSummary(String batchId,java.time.OffsetDateTime openedAt,java.time.OffsetDateTime deadlineAt,java.time.LocalDate arrivalFrom,java.time.LocalDate arrivalTo,int priceTwd,boolean orderingEnabled) {}
    public record Publication(@NotNull @Min(0) Integer expectedVersion,@NotNull Boolean published) {
        @JsonAnySetter public void unknown(String name,Object value){throw new IllegalArgumentException("不支援的欄位");}
    }
    public record AdminView(String id,String title,String brand,String category,String description,
                            String status,int version,List<Sku> skus,ImagePayloads.Image image,String brandId,String categoryId,String workId,String work,
                            Specs specs,String contentOrigin) {}
    public record PublicSku(String id,String label,int priceTwd,boolean available) {}
    /**
     * availability：IN_STOCK／PREORDER／SOLD_OUT（由啟用 SKU 可售量與開放中批次推導）。
     * AI_CONCEPT 商品的 specs.materials 一律為空，設定材質只在 conceptMaterials。
     */
    public record PublicView(String id,String title,String brand,String category,String description,List<PublicSku> skus,ImagePayloads.Image image,String work,
                             Specs specs,String contentOrigin,String availability,Integer minPriceTwd,PreorderSummary preorder,java.time.OffsetDateTime updatedAt) {}
    public record Page<T>(List<T> items,int page,int pageSize,long total) {}
    /** 商品搜尋條件（全部可省略）。多值欄位以 OR 比對，不同欄位以 AND。 */
    public record Search(String q,List<String> brandIds,List<String> workIds,List<String> categoryIds,List<String> manufacturers,
                         List<String> availability,Integer priceMin,Integer priceMax,List<String> scales,List<String> materials,
                         List<String> productTypes,List<String> editions,Boolean limited,String sort) {
        public static Search none(){return new Search(null,null,null,null,null,null,null,null,null,null,null,null,null,null);}
    }
    public static final List<String> AVAILABILITY=List.of("IN_STOCK","PREORDER","SOLD_OUT");
    public static final List<String> SORTS=List.of("NEWEST","PRICE_ASC","PRICE_DESC","RELEASE_ASC","RELEASE_DESC","RELEVANCE");
    public record FacetValue(String value,String label,long count) {}
    public record Facets(List<FacetValue> brands,List<FacetValue> works,List<FacetValue> categories,List<FacetValue> manufacturers,
                         List<FacetValue> availability,List<FacetValue> scales,List<FacetValue> materials,List<FacetValue> productTypes,
                         List<FacetValue> editions,Integer priceMin,Integer priceMax,long total) {}
}
