package dev.jackteng.figura.controller;

import dev.jackteng.figura.pojo.Result;
import dev.jackteng.figura.service.OpsService;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.util.List;

/** Commerce V2 Phase 7：營運報表、低庫存、營運狀態（員工權限由 JwtAuthenticationFilter 依 URL 檢查）。 */
@RestController @RequestMapping("/admin")
public class OpsController {
    private final OpsService ops;
    public OpsController(OpsService ops) { this.ops = ops; }
    @GetMapping("/reports/sales") public Result<OpsService.SalesReport> sales(@RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
                                                                              @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) { return Result.success(ops.sales(from, to)); }
    @GetMapping("/reports/low-stock") public Result<List<OpsService.LowStock>> lowStock(@RequestParam(defaultValue = "3") int threshold) { return Result.success(ops.lowStock(threshold)); }
    @GetMapping("/ops/status") public Result<OpsService.Status> status() { return Result.success(ops.status()); }
}
