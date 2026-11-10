package dev.jackteng.figura.pojo;

import com.fasterxml.jackson.annotation.JsonIgnore;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import lombok.Data;
import java.time.LocalDateTime;
import java.util.List;

@Data
public class User {
    @NotNull
    private Integer id;
    @JsonIgnore private int authVersion;
    private String username;
    @JsonIgnore //讓springmvc將當前對象轉換成json時，忽略password
    private String password;
    private String firstName;
    private String lastName;
    @Email
    private String email;
    private String telephone;
    private String userPic;
    private LocalDateTime lastLoginTime;
    private LocalDateTime createdTime;
    private LocalDateTime modifiedTime;
    private String isStop;
    private List<Role> role;
    private List<Permission> permissions;
}
