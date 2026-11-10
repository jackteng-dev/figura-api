package dev.jackteng.figura.dto;

import lombok.Data;

@Data
public class UserAccount {
    private Integer pageNum;
    private Integer pageSize;
    private String username;
    private Integer[] role;
    private String isStop;
}
