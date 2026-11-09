package com.shop.elpsy.controller;
import com.shop.elpsy.dto.NotificationPayloads.*;
import com.shop.elpsy.pojo.*;
import com.shop.elpsy.service.NotificationService;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.*;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
@RestController @RequestMapping("/admin/notifications")
public class NotificationController {
 private final NotificationService service;public NotificationController(NotificationService service){this.service=service;}
 @GetMapping public Result<?> list(@RequestParam(defaultValue="1") int page,@RequestParam(defaultValue="20") int pageSize,@RequestParam(required=false) String status,@RequestParam(required=false) String orderId){return Result.success(service.list(page,pageSize,status,orderId));}
 @GetMapping("/{id}") public Result<Detail> detail(@PathVariable String id){return Result.success(service.detail(id));}
 @PostMapping("/{id}/mock-dispatch") public Result<Applied> dispatch(@PathVariable String id,@RequestBody @Valid Dispatch body,@AuthenticationPrincipal CustomUserDetails actor){return Result.success(service.dispatch(id,body,actor));}
 @PostMapping("/{id}/retry") public Result<Applied> retry(@PathVariable String id,@RequestBody @Valid Change body,@AuthenticationPrincipal CustomUserDetails actor){return Result.success(service.retry(id,body,actor));}
}
