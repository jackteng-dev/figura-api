package dev.jackteng.figura.controller;
import dev.jackteng.figura.dto.CatalogPayloads.*;
import dev.jackteng.figura.pojo.Result;
import dev.jackteng.figura.pojo.CustomUserDetails;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import dev.jackteng.figura.service.CatalogService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

@RestController
public class CatalogController {
    private final CatalogService catalog;
    public CatalogController(CatalogService catalog){this.catalog=catalog;}
    /** 商品搜尋：舊呼叫只帶 page/pageSize 照舊（最新上架）；其餘參數見 docs/CATALOG_SEARCH_API.md。 */
    @GetMapping("/catalog/products") public Result<Page<PublicView>> list(@RequestParam(defaultValue="1") int page,@RequestParam(defaultValue="12") int pageSize,
            @RequestParam(required=false) String q,@RequestParam(required=false) java.util.List<String> brandId,@RequestParam(required=false) java.util.List<String> workId,
            @RequestParam(required=false) java.util.List<String> categoryId,@RequestParam(required=false) java.util.List<String> manufacturer,
            @RequestParam(required=false) java.util.List<String> availability,@RequestParam(required=false) Integer priceMin,@RequestParam(required=false) Integer priceMax,
            @RequestParam(required=false) java.util.List<String> scale,@RequestParam(required=false) java.util.List<String> material,@RequestParam(required=false) java.util.List<String> productType,
            @RequestParam(required=false) java.util.List<String> edition,@RequestParam(required=false) Boolean limited,@RequestParam(required=false) String sort){
        return Result.success(catalog.search(new Search(q,brandId,workId,categoryId,manufacturer,availability,priceMin,priceMax,scale,material,productType,edition,limited,sort),page,pageSize));}
    @GetMapping("/catalog/facets") public Result<Facets> facets(@RequestParam(required=false) String q,@RequestParam(required=false) java.util.List<String> brandId,@RequestParam(required=false) java.util.List<String> workId,
            @RequestParam(required=false) java.util.List<String> categoryId,@RequestParam(required=false) java.util.List<String> manufacturer,@RequestParam(required=false) Integer priceMin,@RequestParam(required=false) Integer priceMax,
            @RequestParam(required=false) java.util.List<String> scale,@RequestParam(required=false) java.util.List<String> material,@RequestParam(required=false) java.util.List<String> productType,
            @RequestParam(required=false) java.util.List<String> edition,@RequestParam(required=false) Boolean limited){
        return Result.success(catalog.facets(new Search(q,brandId,workId,categoryId,manufacturer,null,priceMin,priceMax,scale,material,productType,edition,limited,null)));}
    @GetMapping("/catalog/products/{id}") public Result<PublicView> detail(@PathVariable String id){return Result.success(catalog.publicDetail(id));}
    @GetMapping("/admin/products") public Result<Page<AdminView>> adminList(@RequestParam(defaultValue="1") int page,@RequestParam(defaultValue="12") int pageSize){return Result.success(catalog.adminList(page,pageSize));}
    @GetMapping("/admin/products/{id}") public Result<AdminView> adminDetail(@PathVariable String id){return Result.success(catalog.adminDetail(id));}
    @PostMapping("/admin/products") @ResponseStatus(HttpStatus.CREATED) public Result<AdminView> create(@Valid @RequestBody Write input,@AuthenticationPrincipal CustomUserDetails actor){return Result.success(catalog.create(input,actor));}
    @PutMapping("/admin/products/{id}") public Result<AdminView> update(@PathVariable String id,@Valid @RequestBody Write input,@AuthenticationPrincipal CustomUserDetails actor){return Result.success(catalog.update(id,input,actor));}
    @PatchMapping("/admin/products/{id}/publication") public Result<AdminView> publish(@PathVariable String id,@Valid @RequestBody Publication input,@AuthenticationPrincipal CustomUserDetails actor){return Result.success(catalog.publication(id,input,actor));}
    @DeleteMapping("/admin/products/{id}") public Result<Void> archive(@PathVariable String id,@RequestParam int expectedVersion,@AuthenticationPrincipal CustomUserDetails actor){catalog.archive(id,expectedVersion,actor);return Result.success();}
}
