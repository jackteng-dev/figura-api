package dev.jackteng.figura.controller;
import dev.jackteng.figura.dto.PreorderFulfillmentPayloads.*;
import dev.jackteng.figura.dto.CatalogPayloads.Page;
import dev.jackteng.figura.pojo.*;
import dev.jackteng.figura.service.PreorderFulfillmentService;
import jakarta.validation.Valid;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
@RestController
public class PreorderFulfillmentController {
 private final PreorderFulfillmentService service;
 public PreorderFulfillmentController(PreorderFulfillmentService service){this.service=service;}
 @GetMapping("/admin/preorder-arrivals") public Result<Page<Supply>> list(@RequestParam(defaultValue="1") int page,@RequestParam(defaultValue="20") int pageSize){return Result.success(service.list(page,pageSize));}
 @GetMapping("/admin/preorder-arrivals/{id}") public Result<BatchDetail> detail(@PathVariable String id){return Result.success(service.batchDetail(id));}
 @PostMapping("/admin/preorder-arrivals/{id}/receive") public Result<Received> receive(@PathVariable String id,@RequestBody @Valid Receive in,@AuthenticationPrincipal CustomUserDetails actor){return Result.success(service.receive(id,in,actor));}
 @PostMapping("/admin/preorder-orders/{id}/allocate") public Result<Applied> allocate(@PathVariable String id,@RequestBody @Valid Allocate in,@AuthenticationPrincipal CustomUserDetails actor){return Result.success(service.allocate(id,in,actor));}
 @PostMapping("/admin/preorder-orders/{id}/mock-ship") public Result<Applied> ship(@PathVariable String id,@RequestBody @Valid Command in,@AuthenticationPrincipal CustomUserDetails actor){return Result.success(service.command(id,"SHIP",in,actor));}
 @PostMapping("/admin/preorder-orders/{id}/mock-deliver") public Result<Applied> deliver(@PathVariable String id,@RequestBody @Valid Command in,@AuthenticationPrincipal CustomUserDetails actor){return Result.success(service.command(id,"DELIVER",in,actor));}
}
