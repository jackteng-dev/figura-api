package com.shop.elpsy.pojo;

import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Data
public class OrderDetails {
    private Integer id;
    private Integer userId;
    private BigDecimal total;
    private Integer paymentId;
    private LocalDateTime createdTime;
    private LocalDateTime modifiedTime;
}
