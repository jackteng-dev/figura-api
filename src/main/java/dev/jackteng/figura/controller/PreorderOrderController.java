package dev.jackteng.figura.controller;
import dev.jackteng.figura.dto.PreorderOrderPayloads.*;
import dev.jackteng.figura.dto.CatalogPayloads.Page;
import dev.jackteng.figura.pojo.*;
import dev.jackteng.figura.service.PreorderOrderService;
import jakarta.validation.Valid;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;

@RestController @RequestMapping({"/customer/preorders","/mobile/customer/preorders"})
public class PreorderOrderController {
 private final PreorderOrderService service;
 public PreorderOrderController(PreorderOrderService service){this.service=service;}
 @GetMapping public Result<Page<View>> list(@AuthenticationPrincipal CustomerPrincipal c,@RequestParam(defaultValue="1") int page,@RequestParam(defaultValue="20") int pageSize){return Result.success(service.list(c.id(),page,pageSize));}
 @GetMapping("/{id}") public Result<View> detail(@AuthenticationPrincipal CustomerPrincipal c,@PathVariable String id){return Result.success(service.detail(c.id(),id));}
 @PostMapping public ResponseEntity<Result<Applied>> place(@AuthenticationPrincipal CustomerPrincipal c,@RequestBody @Valid Place in){var result=service.place(c.id(),in);return ResponseEntity.status(result.replayed()?HttpStatus.OK:HttpStatus.CREATED).body(Result.success(result));}
 @PostMapping("/{id}/cancel") public Result<Applied> cancel(@AuthenticationPrincipal CustomerPrincipal c,@PathVariable String id,@RequestBody @Valid Cancel in){return Result.success(service.cancel(c.id(),id,in));}
}
