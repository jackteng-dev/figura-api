package com.shop.elpsy.controller;
import com.shop.elpsy.dto.MasterPayloads.*;
import com.shop.elpsy.pojo.*;
import com.shop.elpsy.service.CatalogMasterService;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
@RestController @RequestMapping("/admin/masters")
public class CatalogMasterController {
 private final CatalogMasterService masters;public CatalogMasterController(CatalogMasterService masters){this.masters=masters;}
 @GetMapping public Result<?> list(@RequestParam(defaultValue="BRAND") String kind,@RequestParam(defaultValue="1") int page,@RequestParam(defaultValue="20") int pageSize){return Result.success(masters.list(kind,page,pageSize));}
 @GetMapping("/options") public Result<?> options(@RequestParam String kind,@RequestParam(defaultValue="1") int page,@RequestParam(defaultValue="50") int pageSize){return Result.success(masters.list(kind,page,pageSize));}
 @GetMapping("/audit") public Result<?> audit(@RequestParam(defaultValue="1") int page,@RequestParam(defaultValue="20") int pageSize){return Result.success(masters.audit(page,pageSize));}
 @PostMapping("/{kind}") public ResponseEntity<Result<Applied>> create(@PathVariable String kind,@Valid @RequestBody Write input,@AuthenticationPrincipal CustomUserDetails actor){return response(masters.save(kind,null,input,actor));}
 @PutMapping("/{kind}/{id}") public ResponseEntity<Result<Applied>> update(@PathVariable String kind,@PathVariable String id,@Valid @RequestBody Write input,@AuthenticationPrincipal CustomUserDetails actor){return response(masters.save(kind,id,input,actor));}
 private ResponseEntity<Result<Applied>> response(Applied value){return ResponseEntity.status(value.replayed()?200:201).body(Result.success(value));}
}
