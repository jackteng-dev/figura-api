package com.shop.elpsy.pojo;

import lombok.Data;

import java.time.LocalDateTime;

@Data
public class ProductCategory {
    private Integer id;
    private String name;
    private String description;
    private LocalDateTime createdTime;
    private LocalDateTime modifiedTime;
    private LocalDateTime deletedTime;
}
