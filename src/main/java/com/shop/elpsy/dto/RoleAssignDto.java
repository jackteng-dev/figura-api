package com.shop.elpsy.dto;

import lombok.Data;

@Data
public class RoleAssignDto {
    private Integer id;
    private Integer roleId;
    private Integer[] permissionId;
}
