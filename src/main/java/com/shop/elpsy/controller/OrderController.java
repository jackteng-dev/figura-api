package com.shop.elpsy.controller;
import com.shop.elpsy.dto.OrderPayloads.*;
import com.shop.elpsy.dto.CatalogPayloads.Page;
import com.shop.elpsy.pojo.*;
import com.shop.elpsy.service.OrderService;
import jakarta.validation.Valid;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
@RestController @RequestMapping({"/customer/orders","/mobile/customer/orders"})
public class OrderController {
 private final OrderService service;
 public OrderController(OrderService service){this.service=service;}
 @GetMapping public Result<Page<View>> list(@AuthenticationPrincipal CustomerPrincipal user,@RequestParam(defaultValue="1") int page,@RequestParam(defaultValue="20") int pageSize){return Result.success(service.list(user.id(),page,pageSize));}
 @GetMapping("/{id}") public Result<View> detail(@AuthenticationPrincipal CustomerPrincipal user,@PathVariable String id){return Result.success(service.detail(user.id(),id));}
 @PostMapping public Result<Applied> place(@AuthenticationPrincipal CustomerPrincipal user,@RequestBody @Valid Place input){return Result.success(service.place(user.id(),input));}
 @PostMapping("/{id}/cancel") public Result<View> cancel(@AuthenticationPrincipal CustomerPrincipal user,@PathVariable String id,@RequestBody @Valid Cancel input){return Result.success(service.cancel(user.id(),id,input));}
}
