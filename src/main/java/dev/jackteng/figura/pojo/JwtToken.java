package dev.jackteng.figura.pojo;

import lombok.AllArgsConstructor;
import lombok.Data;

@Data
@AllArgsConstructor
public class JwtToken {
    private String refreshToken;
    private String accessToken;

}
