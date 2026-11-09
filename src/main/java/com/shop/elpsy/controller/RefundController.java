package com.shop.elpsy.controller;
import com.shop.elpsy.dto.RefundPayloads.*;
import com.shop.elpsy.dto.FulfillmentPayloads.Change;
import com.shop.elpsy.pojo.*;
import com.shop.elpsy.service.RefundService;
import jakarta.validation.Valid;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
@RestController @RequestMapping("/admin/orders")
public class RefundController {
 private final RefundService service;
 public RefundController(RefundService service){this.service=service;}
 @GetMapping("/{id}/refunds") public Result<Detail> detail(@PathVariable String id){return Result.success(service.detail(id));}
 @PostMapping("/{id}/mock-refund") public Result<Applied> refund(@PathVariable String id,@RequestBody @Valid Change input,@AuthenticationPrincipal CustomUserDetails actor){return Result.success(service.change(id,null,input,actor));}
 @PostMapping("/{id}/mock-review-refund/{payment}") public Result<Applied> review(@PathVariable String id,@PathVariable String payment,@RequestBody @Valid Change input,@AuthenticationPrincipal CustomUserDetails actor){return Result.success(service.change(id,payment,input,actor));}
}
