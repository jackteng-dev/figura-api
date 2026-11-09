package com.shop.elpsy.controller;
import com.shop.elpsy.dto.InventoryPayloads.*;
import com.shop.elpsy.dto.CatalogPayloads.Page;
import com.shop.elpsy.pojo.CustomUserDetails;
import com.shop.elpsy.pojo.Result;
import com.shop.elpsy.service.InventoryService;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

@RestController @RequestMapping("/admin/products/{productId}/skus/{skuId}/inventory")
public class InventoryController {
    private final InventoryService inventory;
    public InventoryController(InventoryService inventory){this.inventory=inventory;}
    @GetMapping public Result<Balance> balance(@PathVariable String productId,@PathVariable String skuId){return Result.success(inventory.balance(productId,skuId));}
    @GetMapping("/adjustments") public Result<Page<Adjustment>> history(@PathVariable String productId,@PathVariable String skuId,@RequestParam(defaultValue="1") int page,@RequestParam(defaultValue="10") int pageSize){return Result.success(inventory.history(productId,skuId,page,pageSize));}
    @PostMapping("/adjustments") public ResponseEntity<Result<Applied>> adjust(@PathVariable String productId,@PathVariable String skuId,@RequestBody @Valid Adjust input,@AuthenticationPrincipal CustomUserDetails actor){var result=inventory.adjust(productId,skuId,input,actor);return ResponseEntity.status(result.replayed()?200:201).body(Result.success(result));}
}
