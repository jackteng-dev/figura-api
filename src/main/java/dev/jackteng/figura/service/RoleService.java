package dev.jackteng.figura.service;

import dev.jackteng.figura.dto.RoleAssignDto;
import dev.jackteng.figura.dto.UserRoleDto;
import dev.jackteng.figura.pojo.Permission;
import dev.jackteng.figura.pojo.Role;

import java.util.List;

public interface RoleService {
    //創健角色
    void createRole(Role role);

    //編輯角色
    void updateRole(Role role);

    //分配角色權限
    void assignRolePermission(RoleAssignDto role);

    //分配使用者角色
    void assignUserRole(UserRoleDto roleDto);

    //獲取所有權限
    List<Permission> findAllPermission();

    //獲取所有角色
    List<Role> findAllRole();

    //查詢角色權限
    List<Permission> getRolePermission(Integer roleId);

    //查詢角色+權限
    List<Role> getAllRolePermission();

    //移除角色權限
    void deleteRolePermission(Integer roleId, Integer[] permission);

    //扁平數據轉樹狀數據
    List<Permission> transferDataToTree(List<Permission> permissions);

    //查詢用戶角色
    List<Role> getUserRole(Integer userId);

    //查詢後台用戶權限
    List<Permission> getUserBackendPermission(Integer id);
}
