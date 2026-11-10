package dev.jackteng.figura.controller;
import dev.jackteng.figura.dto.FulfillmentPayloads.*;
import dev.jackteng.figura.dto.CatalogPayloads.Page;
import dev.jackteng.figura.pojo.*;
import dev.jackteng.figura.service.FulfillmentService;
import jakarta.validation.Valid;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import java.util.List;
@RestController @RequestMapping("/admin/orders")
public class StaffOrderController {
 private final FulfillmentService service;
 public StaffOrderController(FulfillmentService service){this.service=service;}
 @GetMapping public Result<Page<Summary>> list(@RequestParam(defaultValue="1") int page,@RequestParam(defaultValue="10") int pageSize,@RequestParam(required=false) String status,@RequestParam(required=false) String fulfillmentStatus){return Result.success(service.list(page,pageSize,status,fulfillmentStatus));}
 @GetMapping("/{id}") public Result<Detail> detail(@PathVariable String id){return Result.success(service.detail(id));}
 @GetMapping("/{id}/fulfillment-events") public Result<List<Audit>> history(@PathVariable String id){return Result.success(service.history(id));}
 @PostMapping("/{id}/mock-shipment") public Result<Applied> ship(@PathVariable String id,@RequestBody @Valid Change input,@AuthenticationPrincipal CustomUserDetails actor){return Result.success(service.change(id,"SHIP",input,actor));}
 @PostMapping("/{id}/mock-delivery") public Result<Applied> deliver(@PathVariable String id,@RequestBody @Valid Change input,@AuthenticationPrincipal CustomUserDetails actor){return Result.success(service.change(id,"DELIVER",input,actor));}
}
