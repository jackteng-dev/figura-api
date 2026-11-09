package com.shop.elpsy.controller;
import com.shop.elpsy.dto.PreorderMoneyPayloads.*;
import com.shop.elpsy.dto.PaymentPayloads;
import com.shop.elpsy.dto.CatalogPayloads.Page;
import com.shop.elpsy.dto.PreorderOrderPayloads.View;
import com.shop.elpsy.pojo.*;
import com.shop.elpsy.service.*;
import jakarta.validation.Valid;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
@RestController
public class PreorderMoneyController {
 private final PreorderMoneyService service;private final PreorderOrderService orders;
 public PreorderMoneyController(PreorderMoneyService service,PreorderOrderService orders){this.service=service;this.orders=orders;}
 @GetMapping({"/customer/preorders/{id}/mock-payments","/mobile/customer/preorders/{id}/mock-payments"}) public Result<Options> options(@AuthenticationPrincipal CustomerPrincipal c,@PathVariable String id){return Result.success(service.options(c.id(),id));}
 @PostMapping({"/customer/preorders/{id}/mock-payments","/mobile/customer/preorders/{id}/mock-payments"}) public Result<Started> start(@AuthenticationPrincipal CustomerPrincipal c,@PathVariable String id,@RequestBody @Valid PaymentPayloads.Start in){return Result.success(service.start(c.id(),id,in));}
 @PostMapping({"/customer/preorder-payments/{id}/simulate","/mobile/customer/preorder-payments/{id}/simulate"}) public Result<Receipt> simulate(@AuthenticationPrincipal CustomerPrincipal c,@PathVariable String id,@RequestBody @Valid PaymentPayloads.Simulate in){return Result.success(service.simulate(c.id(),id,in));}
 @PostMapping(value="/preorder-payments/mock/callback",consumes="application/json") public Result<PaymentPayloads.CallbackAck> callback(@RequestHeader(value="X-Mock-Timestamp",required=false) String ts,@RequestHeader(value="X-Mock-Signature",required=false) String signature,@RequestBody String raw){var result=service.receive(ts,signature,raw);return Result.success(new PaymentPayloads.CallbackAck(result.eventId(),result.disposition(),result.replayed(),result.payment().id(),result.order().id()));}
 @GetMapping("/admin/preorder-orders") public Result<Page<Summary>> list(@RequestParam(defaultValue="1") int page,@RequestParam(defaultValue="20") int pageSize,@RequestParam(required=false) String status){return Result.success(orders.staffList(page,pageSize,status));}
 @GetMapping("/admin/preorder-orders/{id}") public Result<Detail> detail(@PathVariable String id){return Result.success(service.detail(id));}
 @PostMapping("/admin/preorder-orders/{id}/mock-refund") public Result<Applied> refund(@PathVariable String id,@RequestBody @Valid Refund in,@AuthenticationPrincipal CustomUserDetails actor){return Result.success(service.refund(id,null,in,actor));}
 @PostMapping("/admin/preorder-orders/{id}/mock-review-refund/{payment}") public Result<Applied> review(@PathVariable String id,@PathVariable String payment,@RequestBody @Valid Refund in,@AuthenticationPrincipal CustomUserDetails actor){return Result.success(service.refund(id,payment,in,actor));}
}
