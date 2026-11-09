package com.shop.elpsy.controller;
import com.shop.elpsy.dto.ReturnPayloads.*;
import com.shop.elpsy.dto.FulfillmentPayloads.Change;
import com.shop.elpsy.pojo.*;
import com.shop.elpsy.service.*;
import jakarta.validation.Valid;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
@RestController
public class ReturnController {
 private final ReturnService service;private final RefundService refunds;
 public ReturnController(ReturnService service,RefundService refunds){this.service=service;this.refunds=refunds;}
 @GetMapping({"/customer/orders/{id}/mock-return","/mobile/customer/orders/{id}/mock-return"}) public Result<Options> options(@PathVariable String id,@AuthenticationPrincipal CustomerPrincipal c){return Result.success(service.options(c.id(),id));}
 @PostMapping({"/customer/orders/{id}/mock-return","/mobile/customer/orders/{id}/mock-return"}) public Result<Requested> request(@PathVariable String id,@RequestBody @Valid Request input,@AuthenticationPrincipal CustomerPrincipal c){return Result.success(service.request(c.id(),id,input));}
 @GetMapping("/admin/orders/{id}/returns") public Result<Detail> detail(@PathVariable String id){return Result.success(service.detail(id));}
 @PostMapping("/admin/orders/{id}/mock-return-receipt") public Result<Applied> receive(@PathVariable String id,@RequestBody @Valid Receive input,@AuthenticationPrincipal CustomUserDetails a){return Result.success(service.change(id,"RECEIVE",new Change(input.requestId(),input.expectedOrderVersion(),input.note()),input.disposition(),a));}
 @PostMapping("/admin/orders/{id}/mock-return-rejection") public Result<Applied> reject(@PathVariable String id,@RequestBody @Valid Change input,@AuthenticationPrincipal CustomUserDetails a){return Result.success(service.change(id,"REJECT",input,null,a));}
 @PostMapping("/admin/orders/{id}/mock-return-refund") public Result<com.shop.elpsy.dto.RefundPayloads.Applied> refund(@PathVariable String id,@RequestBody @Valid Change input,@AuthenticationPrincipal CustomUserDetails a){return Result.success(refunds.changeReturned(id,input,a));}
}
