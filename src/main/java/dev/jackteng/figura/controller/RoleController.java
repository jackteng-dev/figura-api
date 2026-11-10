package dev.jackteng.figura.controller;

import dev.jackteng.figura.dto.RoleAssignDto;
import dev.jackteng.figura.dto.UserRoleDto;
import dev.jackteng.figura.pojo.*;
import dev.jackteng.figura.service.RoleService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/role")
@Validated
public class RoleController {
    @Autowired
    private RoleService roleService;

    //新增角色
    @PostMapping("/create")
    public Result createRole(@RequestBody Role role) {
        roleService.createRole(role);
        return Result.success();
    }

    //編輯角色
    @PutMapping("/update")
    public Result updateRole(@RequestBody Role role) {
        roleService.updateRole(role);
        return Result.success();
    }

    //分配角色權限
    @PostMapping("/assignPermission")
    public Result assignPermissionToRole(@RequestBody RoleAssignDto roleDto) {
        roleService.assignRolePermission(roleDto);
        return Result.success();
    }

    //查詢用戶角色
    @GetMapping("/userRole")
    public Result<List<Role>> getUserRole(@RequestParam Integer userId) {
//        System.out.println(userId);
        return Result.success(roleService.getUserRole(userId));
    }

    //分配用戶角色
    @PostMapping("/assignUser")
    public Result assignUserRole(@RequestBody UserRoleDto roleDto) {
        roleService.assignUserRole(roleDto);
        return Result.success();
    }

    //查詢所有權限
    @GetMapping("/permission")
    public  Result<List<Permission>> getAllPermission(){
        return Result.success(roleService.findAllPermission());
    }

    //查詢所有權限-tree分級
    @GetMapping("/permissionTree")
    public  Result<List<Permission>> getAllPermissionTree(){
//        System.out.println(roleService.findAllPermission());
//        System.out.println(roleService.transferDataToTree(roleService.findAllPermission()));
        return Result.success(roleService.transferDataToTree(roleService.findAllPermission()));
    }



    //查詢所有角色+權限
    @GetMapping("/rolePermission")
    public  Result<List<Role>> getAllRolePermission(){
        return Result.success(roleService.getAllRolePermission());
    }

    //查詢角色權限
    @PostMapping("/check")
    public Result<List<Permission>> getRolePermission(Integer roleId){
        return Result.success(roleService.getRolePermission(roleId));
    }

    //移除角色權限
    @DeleteMapping("/deletePermission")
    public Result deleteRolePermission(@RequestBody(required = false) RoleAssignDto data){
//        System.out.println(data.toString());
        roleService.deleteRolePermission(data.getRoleId(),data.getPermissionId());
        return Result.success();
    }

}
