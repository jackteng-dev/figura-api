package com.shop.elpsy.controller;
import com.shop.elpsy.dto.CatalogPayloads.*;
import com.shop.elpsy.pojo.Result;
import com.shop.elpsy.pojo.CustomUserDetails;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import com.shop.elpsy.service.CatalogService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

@RestController
public class CatalogController {
    private final CatalogService catalog;
    public CatalogController(CatalogService catalog){this.catalog=catalog;}
    @GetMapping("/catalog/products") public Result<Page<PublicView>> list(@RequestParam(defaultValue="1") int page,@RequestParam(defaultValue="12") int pageSize){return Result.success(catalog.publicList(page,pageSize));}
    @GetMapping("/catalog/products/{id}") public Result<PublicView> detail(@PathVariable String id){return Result.success(catalog.publicDetail(id));}
    @GetMapping("/admin/products") public Result<Page<AdminView>> adminList(@RequestParam(defaultValue="1") int page,@RequestParam(defaultValue="12") int pageSize){return Result.success(catalog.adminList(page,pageSize));}
    @GetMapping("/admin/products/{id}") public Result<AdminView> adminDetail(@PathVariable String id){return Result.success(catalog.adminDetail(id));}
    @PostMapping("/admin/products") @ResponseStatus(HttpStatus.CREATED) public Result<AdminView> create(@Valid @RequestBody Write input,@AuthenticationPrincipal CustomUserDetails actor){return Result.success(catalog.create(input,actor));}
    @PutMapping("/admin/products/{id}") public Result<AdminView> update(@PathVariable String id,@Valid @RequestBody Write input,@AuthenticationPrincipal CustomUserDetails actor){return Result.success(catalog.update(id,input,actor));}
    @PatchMapping("/admin/products/{id}/publication") public Result<AdminView> publish(@PathVariable String id,@Valid @RequestBody Publication input,@AuthenticationPrincipal CustomUserDetails actor){return Result.success(catalog.publication(id,input,actor));}
    @DeleteMapping("/admin/products/{id}") public Result<Void> archive(@PathVariable String id,@RequestParam int expectedVersion,@AuthenticationPrincipal CustomUserDetails actor){catalog.archive(id,expectedVersion,actor);return Result.success();}
}
