package com.shop.elpsy.dto;

import com.shop.elpsy.pojo.JwtToken;
import com.shop.elpsy.pojo.User;
import lombok.AllArgsConstructor;
import lombok.Data;

@Data
@AllArgsConstructor
public class LoginResponseDTO {
    private JwtToken jwtToken;
    private User user;
}
