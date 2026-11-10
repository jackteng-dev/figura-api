package dev.jackteng.figura.dto;

import lombok.Data;

@Data
public class RoleAssignDto {
    private Integer id;
    private Integer roleId;
    private Integer[] permissionId;
}
