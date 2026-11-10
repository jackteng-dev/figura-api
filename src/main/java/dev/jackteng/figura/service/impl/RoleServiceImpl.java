package dev.jackteng.figura.service.impl;

import dev.jackteng.figura.dto.RoleAssignDto;
import dev.jackteng.figura.dto.UserRoleDto;
import dev.jackteng.figura.mapper.RoleMapper;
import dev.jackteng.figura.pojo.Permission;
import dev.jackteng.figura.pojo.Role;
import dev.jackteng.figura.service.RoleService;
import org.apache.commons.lang3.ArrayUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.*;
import java.util.function.Function;
import java.util.stream.Collectors;

@Service
public class RoleServiceImpl implements RoleService {

    @Autowired
    private RoleMapper roleMapper;

    @Override
    public void createRole(Role role) {
//        System.out.println(role.toString());
        roleMapper.createRole(role);
    }

    @Override
    public void updateRole(Role role) {
        roleMapper.updateRole(role);
    }

    @Transactional
    @Override
    public void assignRolePermission(RoleAssignDto roleDto) {
//        System.out.println(roleDto.toString());
        roleMapper.clearPermissionById(roleDto.getRoleId());
        for (Integer PermissionId : roleDto.getPermissionId()) {
            roleMapper.assignRolePermission(roleDto.getRoleId(), PermissionId);
        }
    }

    @Transactional
    @Override
    public void assignUserRole(UserRoleDto roleDto) {
        roleMapper.clearUserRoleById(roleDto.getUserId());
        for (Integer RoleId : roleDto.getRoleId()) {
            roleMapper.assignUserRole(roleDto.getUserId(), RoleId);
        }
    }

    @Override
    public List<Permission> findAllPermission() {
        return roleMapper.findAllPermission();
    }

    @Override
    public List<Role> findAllRole() {
        return roleMapper.findAllRole();
    }

    @Override
    public List<Permission> getRolePermission(Integer roleId) {
        return transferDataToTree(roleMapper.getRolePermission(roleId));
    }

    //獲取所有角色+權限
    @Override
    public List<Role> getAllRolePermission() {
        List<Role> roles = roleMapper.getAllRolePermission();
//        System.out.println(roles);

        // 对于每个角色，构建其权限的树状结构
        for (Role role : roles) {
            List<Permission> rootPermissions = transferDataToTree(role.getPermission()); // 假设这里获取到了扁平的权限列表
            role.setPermission(rootPermissions);
        }

        return roles;
    }

    //扁平數據轉樹狀數據
    @Override
    public  List<Permission> transferDataToTree(List<Permission> permissions){
        List<Permission> rootPermissions = new ArrayList<>();
        // 用一个映射来快速查找权限
        Map<Integer, Permission> permissionMap = permissions.stream()
                .collect(Collectors.toMap(Permission::getId, Function.identity()));

        for (Permission permission : permissions) {
            if (permission.getParentId() == null || permission.getParentId() == 0) {
                // 根权限，没有父权限
                rootPermissions.add(permission);
            } else {
                // 子权限，添加到父权限的子列表中
                Permission parent = permissionMap.get(permission.getParentId());
                if (parent != null) {
                    if (parent.getChildren() == null) {
                        parent.setChildren(new ArrayList<>());
                    }
                    parent.getChildren().add(permission);
                }
            }
        }
//        System.out.println(rootPermissions);
        return rootPermissions;
    }

    //查詢用戶角色
    @Override
    public List<Role> getUserRole(Integer userId) {
        return roleMapper.getUserRole(userId);
    }

    //查詢後台用戶權限
    @Override
    public List<Permission> getUserBackendPermission(Integer id) {
        return roleMapper.getUserBackendPermission(id);
    }

    //刪除權限
    @Override
    @Transactional
    public void deleteRolePermission(Integer roleId, Integer[] permission) {
        roleMapper.deleteRolePermission(roleId, permission);
        //檢查角色剩餘的權限
        Integer[] rolePermission = roleMapper.getRolePermissionInteger(roleId);
        for (Integer permissionId : rolePermission) {
            //檢查一級權限
            if (roleMapper.getPermissionById(permissionId).getLevel() == 1) {
                Integer[] childPermissions = roleMapper.getChildIdByParent(permissionId);
                Set<Integer> set = Arrays.stream(rolePermission).collect(Collectors.toSet());
                boolean found = Arrays.stream(childPermissions).anyMatch(set::contains);
                if (!found) {
                    //當權限沒有子元素刪除權限
                    roleMapper.deleteRolePermission(roleId, new Integer[]{permissionId});
                    rolePermission = ArrayUtils.removeElement(rolePermission, permissionId);
                }
            }
        }
        for (Integer permissionId : rolePermission) {
            //檢查二級權限
            if (roleMapper.getPermissionById(permissionId).getLevel() == 0) {
                Integer[] childPermissions = roleMapper.getChildIdByParent(permissionId);
                Set<Integer> set = Arrays.stream(rolePermission).collect(Collectors.toSet());
                boolean found = Arrays.stream(childPermissions).anyMatch(set::contains);
                if (!found) {
                    //當權限沒有子元素刪除權限
                    roleMapper.deleteRolePermission(roleId, new Integer[]{permissionId});
                }
            }
        }
    }


}
