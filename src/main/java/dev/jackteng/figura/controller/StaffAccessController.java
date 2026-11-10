package dev.jackteng.figura.controller;
import dev.jackteng.figura.dto.AccessPayloads.*;
import dev.jackteng.figura.pojo.CustomUserDetails;
import dev.jackteng.figura.pojo.Result;
import dev.jackteng.figura.service.StaffAccessService;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

@RestController @RequestMapping("/admin/access")
public class StaffAccessController {
 private final StaffAccessService access;
 public StaffAccessController(StaffAccessService access){this.access=access;}
 @GetMapping("/staff") public Result<?> staff(@RequestParam(defaultValue="1") int page,@RequestParam(defaultValue="20") int pageSize){return Result.success(access.staffList(page,pageSize));}
 @GetMapping("/roles") public Result<?> roles(@RequestParam(defaultValue="1") int page,@RequestParam(defaultValue="50") int pageSize){return Result.success(access.roles(page,pageSize));}
 @GetMapping("/permissions") public Result<?> permissions(){return Result.success(access.permissions());}
 @GetMapping("/audit") public Result<?> audit(@RequestParam(defaultValue="1") int page,@RequestParam(defaultValue="20") int pageSize){return Result.success(access.audit(page,pageSize));}
 @PostMapping("/staff") public ResponseEntity<Result<Applied>> createStaff(@Valid @RequestBody StaffCreate input,@AuthenticationPrincipal CustomUserDetails actor){return response(access.createStaff(input,actor));}
 @PutMapping("/staff/{id}") public ResponseEntity<Result<Applied>> updateStaff(@PathVariable int id,@Valid @RequestBody StaffWrite input,@AuthenticationPrincipal CustomUserDetails actor){return response(access.updateStaff(id,input,actor));}
 @PostMapping("/roles") public ResponseEntity<Result<Applied>> createRole(@Valid @RequestBody RoleWrite input,@AuthenticationPrincipal CustomUserDetails actor){return response(access.saveRole(null,input,actor));}
 @PutMapping("/roles/{id}") public ResponseEntity<Result<Applied>> updateRole(@PathVariable int id,@Valid @RequestBody RoleWrite input,@AuthenticationPrincipal CustomUserDetails actor){return response(access.saveRole(id,input,actor));}
 private ResponseEntity<Result<Applied>> response(Applied value){return ResponseEntity.status(value.replayed()?200:201).body(Result.success(value));}
}
