package dev.jackteng.figura.dto;

import dev.jackteng.figura.pojo.JwtToken;
import dev.jackteng.figura.pojo.User;
import lombok.AllArgsConstructor;
import lombok.Data;

@Data
@AllArgsConstructor
public class LoginResponseDTO {
    private JwtToken jwtToken;
    private User user;
}
