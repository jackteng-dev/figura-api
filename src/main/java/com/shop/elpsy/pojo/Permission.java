package com.shop.elpsy.pojo;

import com.fasterxml.jackson.annotation.JsonIgnore;
import lombok.Data;

import java.util.ArrayList;
import java.util.List;


@Data
public class Permission {
    private Integer id;
    private String name;
    private String description;
    private Integer parentId;
    private Integer level;
    private String url;
    private String method;
    private String adminRouter;
    private String code;
//    @JsonIgnore
    private List<Permission> children = new ArrayList<>();
}
