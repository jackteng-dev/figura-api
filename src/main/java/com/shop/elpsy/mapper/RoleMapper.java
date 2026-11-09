package com.shop.elpsy.mapper;

import com.shop.elpsy.pojo.Permission;
import com.shop.elpsy.pojo.Role;
import org.apache.ibatis.annotations.*;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Mapper
public interface RoleMapper {

    //創建角色
    @Insert("insert into role(name,description) " +
            "values(#{name},#{description})")
    void createRole(Role role);

    //編輯角色
    @Update("update role set name = #{name},description = #{description} where id = #{id}")
    void updateRole(Role role);

    //查詢角色
    @Select("select * from role where id = #{roleId}")
    Role findRoleById(Integer roleId);

    //清除角色權限
    @Delete("delete from role_permission where role_id = #{roleId}")
    void clearPermissionById(Integer roleId);

    //分配角色權限
    @Insert("insert into role_permission(role_id,permission_id) values(#{id},#{permissionId})")
    void assignRolePermission(Integer id, Integer permissionId);

    //清除用戶角色
    @Delete("delete from user_role where user_id = #{userId}")
    void clearUserRoleById(Integer userId);

    //分配用戶角色
    @Insert("insert into user_role(user_id,role_id) values(#{userId},#{roleId})")
    void assignUserRole(Integer userId, Integer roleId);

    //獲取所有權限
    @Select("select * from permissions_backend")
    List<Permission> findAllPermission();

    //獲取所有角色
    @Select("select * from role")
    List<Role> findAllRole();

    //查詢角色權限Integer[]
    @Select("select permission_id from role_permission where role_id = #{roleId}")
    Integer[] getRolePermissionInteger(Integer roleId);

    //查詢角色權限
    @Select("select p.id,p.name,p.description,p.parent_id,p.level,p.url,p.method,p.admin_router,p.code from role_permission rp join permissions_backend p on rp.permission_id = p.id where rp.role_id = #{roleId}")
    List<Permission> getRolePermission(Integer roleId);

    //查詢角色+權限
//    @Select("select role.id,role.name,role.description,role_permission.permission_id,permission.name,permission.level from role join role_permission on role.id = role_permission.role_id " +
//            "join permissions_backend on role_permission.permission_id = permission.id")
    List<Role> getAllRolePermission();

    @Transactional
    @Delete({"<script>" +
            "delete from role_permission where role_id = #{roleId} and permission_id In " +
            "<foreach collection=\"permissionId\" item=\"permissionId\" open=\"(\" separator=\",\" close=\")\">" +
            "#{permissionId}" +
            "</foreach>" +
            "</script>"})
    void deleteRolePermission(Integer roleId, Integer[] permissionId);

    //查詢權限
    @Select("select * from permissions_backend where id = #{id}")
    Permission getPermissionById(Integer id);

    //獲取子權限列表
    @Select("select id from permissions_backend where parent_id = #{parentId}")
    Integer[] getChildIdByParent(Integer parentId);

    //查詢用戶角色
    @Select("select r.id,r.name,r.description from user_role ur join role r on ur.role_id = r.id where ur.user_id = #{userId}")
    List<Role> getUserRole(Integer userId);

    //查詢後台用戶權限
    @Select("select distinct p.* from user u join user_role ur on u.id = ur.user_id join role r on ur.role_id = r.id join role_permission rp on r.id = rp.role_id join permissions_backend p on rp.permission_id = p.id where u.id = #{id} and r.enabled=TRUE")
    List<Permission> getUserBackendPermission(Integer id);

    //查詢前台用戶權限
    @Select("select distinct p.* from user u join user_role ur on u.id = ur.user_id join role r on ur.role_id = r.id join role_permission rp on r.id = rp.role_id join permissions_frontend p on rp.permission_id = p.id where u.id = #{id} and r.enabled=TRUE")
    List<Permission> getUserFrontendPermission(Integer id);
}
