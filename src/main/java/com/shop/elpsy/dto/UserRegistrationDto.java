package com.shop.elpsy.dto;

import com.fasterxml.jackson.annotation.JsonIgnore;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.Pattern;
import lombok.Data;

import java.time.LocalDateTime;

@Data
public class UserRegistrationDto {
    @Pattern(regexp = "^[a-zA-Z0-9_]{6,20}$",message = "用戶名格式不正確")
    private String username;
    // 密码长度8-20位且至少包含大写字母、小写字母、数字或特殊符号中的任意三种
    @Pattern(regexp = "^(?![a-zA-Z]+$)(?![A-Z0-9]+$)(?![A-Z\\W_]+$)(?![a-z0-9]+$)(?![a-z\\W_]+$)(?![0-9\\W_]+$)[a-zA-Z0-9\\W_]{8,20}$"
    ,message = "密碼格式不正確")
    private String rePassword;
    private String password;
    private String firstName;
    private String lastName;
    @Email
    private String email;
    private String telephone;
    private String role;
    private String isStop = "F";
}
