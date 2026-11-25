package dev.jackteng.figura.controller;

import dev.jackteng.figura.dto.WarehousePayloads.*;
import dev.jackteng.figura.pojo.CustomUserDetails;
import dev.jackteng.figura.pojo.CustomerPrincipal;
import dev.jackteng.figura.pojo.Result;
import dev.jackteng.figura.service.WarehouseService;
import jakarta.validation.Valid;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

/** Commerce V2 Phase 5：顧客出貨單合併／私人倉庫，與員工倉庫、運費調整。 */
@RestController
public class WarehouseController {
    private final WarehouseService service;
    public WarehouseController(WarehouseService service) { this.service = service; }

    @GetMapping({"/customer/shipments", "/mobile/customer/shipments"})
    public Result<CustomerPage> list(@AuthenticationPrincipal CustomerPrincipal c, @RequestParam(defaultValue = "1") int page, @RequestParam(defaultValue = "20") int pageSize) { return Result.success(service.list(c.id(), page, pageSize)); }
    @PostMapping({"/customer/shipments/merge", "/mobile/customer/shipments/merge"})
    public Result<Merged> merge(@AuthenticationPrincipal CustomerPrincipal c, @RequestBody @Valid Merge in) { return Result.success(service.merge(c.id(), in)); }
    @PostMapping({"/customer/shipments/{id}/hold", "/mobile/customer/shipments/{id}/hold"})
    public Result<Held> hold(@AuthenticationPrincipal CustomerPrincipal c, @PathVariable String id, @RequestBody @Valid HoldCommand in) { return Result.success(service.hold(c.id(), id, in, true)); }
    @PostMapping({"/customer/shipments/{id}/release", "/mobile/customer/shipments/{id}/release"})
    public Result<Held> release(@AuthenticationPrincipal CustomerPrincipal c, @PathVariable String id, @RequestBody @Valid HoldCommand in) { return Result.success(service.hold(c.id(), id, in, false)); }

    @GetMapping("/admin/warehouse") public Result<WarehousePage> warehouse(@RequestParam(defaultValue = "1") int page, @RequestParam(defaultValue = "20") int pageSize, @RequestParam(defaultValue = "false") boolean overdue) { return Result.success(service.warehouse(page, pageSize, overdue)); }
    @PostMapping("/admin/warehouse/{id}/release") public Result<StaffApplied> staffRelease(@PathVariable String id, @RequestBody @Valid StaffCommand in, @AuthenticationPrincipal CustomUserDetails actor) { return Result.success(service.staffHold(id, "RELEASE_HOLD", in, actor)); }
    @PostMapping("/admin/warehouse/{id}/extend") public Result<StaffApplied> staffExtend(@PathVariable String id, @RequestBody @Valid StaffCommand in, @AuthenticationPrincipal CustomUserDetails actor) { return Result.success(service.staffHold(id, "EXTEND_HOLD", in, actor)); }
    @GetMapping("/admin/shipping-adjustments") public Result<AdjustmentPage> adjustments(@RequestParam(defaultValue = "1") int page, @RequestParam(defaultValue = "20") int pageSize, @RequestParam(required = false) String status) { return Result.success(service.adjustments(page, pageSize, status)); }
    @PostMapping("/admin/shipping-adjustments/{id}/decide") public Result<StaffApplied> decide(@PathVariable String id, @RequestBody @Valid Decide in, @AuthenticationPrincipal CustomUserDetails actor) { return Result.success(service.decide(id, in, actor)); }
}
