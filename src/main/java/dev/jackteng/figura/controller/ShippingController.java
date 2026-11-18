package dev.jackteng.figura.controller;

import dev.jackteng.figura.dto.ShippingPayloads.*;
import dev.jackteng.figura.pojo.CustomUserDetails;
import dev.jackteng.figura.pojo.Result;
import dev.jackteng.figura.service.ShipmentService;
import dev.jackteng.figura.service.ShippingService;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/** 員工端：配送方式與運費規則、出貨單（測試物流）。權限由 permissions_backend 的 URL／method 與服務內即時授權共同控管。 */
@RestController @RequestMapping("/admin")
public class ShippingController {
    private final ShippingService shipping; private final ShipmentService shipments;
    public ShippingController(ShippingService shipping, ShipmentService shipments) { this.shipping = shipping; this.shipments = shipments; }
    @GetMapping("/shipping-methods") public Result<List<Method>> methods() { return Result.success(shipping.list()); }
    @PutMapping("/shipping-methods/{code}") public Result<MethodApplied> update(@PathVariable String code, @Valid @RequestBody MethodWrite in, @AuthenticationPrincipal CustomUserDetails actor) {
        if (!code.matches("HOME|CVS")) throw new org.springframework.web.server.ResponseStatusException(org.springframework.http.HttpStatus.NOT_FOUND, "找不到配送方式");
        return Result.success(shipping.update(code, in, actor));
    }
    @GetMapping("/shipments") public Result<Page> list(@RequestParam(defaultValue = "1") int page, @RequestParam(defaultValue = "20") int pageSize) { return Result.success(shipments.list(page, pageSize)); }
    @GetMapping("/shipments/{id}") public Result<Detail> detail(@PathVariable String id) { return Result.success(shipments.detail(id)); }
    @PostMapping("/shipments/{id}/ship") public ResponseEntity<Result<Applied>> ship(@PathVariable String id, @Valid @RequestBody Command in, @AuthenticationPrincipal CustomUserDetails actor) { return applied(shipments.command(id, "SHIP", in, actor)); }
    @PostMapping("/shipments/{id}/deliver") public ResponseEntity<Result<Applied>> deliver(@PathVariable String id, @Valid @RequestBody Command in, @AuthenticationPrincipal CustomUserDetails actor) { return applied(shipments.command(id, "DELIVER", in, actor)); }
    private ResponseEntity<Result<Applied>> applied(Applied a) { return ResponseEntity.status(a.replayed() ? 200 : 201).body(Result.success(a)); }
}
