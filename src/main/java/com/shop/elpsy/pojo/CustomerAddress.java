package com.shop.elpsy.pojo;
import lombok.Data;
@Data
public class CustomerAddress {
    private String id,customerId,recipientName,phone,postalCode,city,district,street;
    private int version;
    private boolean deleted;
}
