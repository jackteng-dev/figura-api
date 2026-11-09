package com.shop.elpsy.controller;

import com.shop.elpsy.dto.PreorderPayloads.*;
import com.shop.elpsy.pojo.*;
import com.shop.elpsy.service.PreorderBatchService;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

@RestController
public class PreorderBatchController {
    private final PreorderBatchService batches;
    public PreorderBatchController(PreorderBatchService batches){this.batches=batches;}
    @GetMapping("/admin/preorders") public Result<?> list(@RequestParam(defaultValue="1") int page,@RequestParam(defaultValue="20") int pageSize){return Result.success(batches.list(page,pageSize));}
    @GetMapping("/admin/preorders/{id}") public Result<?> detail(@PathVariable String id){return Result.success(batches.detail(id));}
    @PostMapping("/admin/preorders") public ResponseEntity<Result<Applied>> create(@Valid @RequestBody Write input,@AuthenticationPrincipal CustomUserDetails actor){return applied(batches.save(null,input,actor));}
    @PutMapping("/admin/preorders/{id}") public ResponseEntity<Result<Applied>> update(@PathVariable String id,@Valid @RequestBody Write input,@AuthenticationPrincipal CustomUserDetails actor){return applied(batches.save(id,input,actor));}
    @PostMapping("/admin/preorders/{id}/open") public ResponseEntity<Result<Applied>> open(@PathVariable String id,@Valid @RequestBody Command input,@AuthenticationPrincipal CustomUserDetails actor){return applied(batches.command(id,"OPEN",input,actor));}
    @PostMapping("/admin/preorders/{id}/close") public ResponseEntity<Result<Applied>> close(@PathVariable String id,@Valid @RequestBody Command input,@AuthenticationPrincipal CustomUserDetails actor){return applied(batches.command(id,"CLOSE",input,actor));}
    @GetMapping("/catalog/preorders") public ResponseEntity<Result<?>> publicList(@RequestParam(defaultValue="1") int page,@RequestParam(defaultValue="12") int pageSize){return ResponseEntity.ok().header("Cache-Control","no-store").body(Result.success(batches.publicList(page,pageSize)));}
    @GetMapping("/catalog/preorders/{id}") public ResponseEntity<Result<?>> publicDetail(@PathVariable String id){return ResponseEntity.ok().header("Cache-Control","no-store").body(Result.success(batches.publicDetail(id)));}
    private ResponseEntity<Result<Applied>> applied(Applied value){return ResponseEntity.status(value.replayed()?200:201).body(Result.success(value));}
}
