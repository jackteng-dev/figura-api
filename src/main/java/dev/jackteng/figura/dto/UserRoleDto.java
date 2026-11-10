package dev.jackteng.figura.dto;

import lombok.Data;

@Data
public class UserRoleDto {
    private Integer id;
    private Integer userId;
    private Integer[] roleId;
}
