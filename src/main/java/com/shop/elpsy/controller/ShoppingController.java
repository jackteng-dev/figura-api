package com.shop.elpsy.controller;

import com.shop.elpsy.dto.ShoppingPayloads.*;
import com.shop.elpsy.pojo.CustomerPrincipal;
import com.shop.elpsy.pojo.Result;
import com.shop.elpsy.service.ShoppingService;
import jakarta.validation.Valid;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import java.util.List;

@RestController
@RequestMapping({"/customer","/mobile/customer"})
public class ShoppingController {
    private final ShoppingService service;
    public ShoppingController(ShoppingService service){this.service=service;}
    @GetMapping("/cart") public Result<CartView> cart(@AuthenticationPrincipal CustomerPrincipal user){return Result.success(service.cart(user.id()));}
    @PutMapping("/cart/items/{skuId}") public Result<CartView> set(@AuthenticationPrincipal CustomerPrincipal user,@PathVariable String skuId,@RequestBody @Valid CartWrite input){return Result.success(service.setItem(user.id(),skuId,input));}
    @DeleteMapping("/cart/items/{skuId}") public Result<CartView> remove(@AuthenticationPrincipal CustomerPrincipal user,@PathVariable String skuId,@RequestParam int expectedVersion){return Result.success(service.removeItem(user.id(),skuId,expectedVersion));}
    @GetMapping("/addresses") public Result<List<AddressView>> addresses(@AuthenticationPrincipal CustomerPrincipal user){return Result.success(service.addresses(user.id()));}
    @PutMapping("/addresses/{id}") public Result<AddressView> address(@AuthenticationPrincipal CustomerPrincipal user,@PathVariable String id,@RequestBody @Valid AddressWrite input){return Result.success(service.saveAddress(user.id(),id,input));}
    @DeleteMapping("/addresses/{id}") public Result<Void> delete(@AuthenticationPrincipal CustomerPrincipal user,@PathVariable String id,@RequestParam int expectedVersion){service.deleteAddress(user.id(),id,expectedVersion);return Result.success();}
}
