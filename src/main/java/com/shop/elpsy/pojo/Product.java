package com.shop.elpsy.pojo;

import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Data
public class Product {
    private Integer id;
    private String name;
    private String description;
    private String sku;
    private Integer categoryId;
    private Integer inventoryId;
    private BigDecimal price;
    private Integer discountId;
    private LocalDateTime createdTime;
    private LocalDateTime modifiedTime;
    private LocalDateTime deletedTime;

}
