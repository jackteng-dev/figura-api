package dev.jackteng.figura.controller;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.jackteng.figura.dto.LoginResponseDTO;
import dev.jackteng.figura.dto.UserAccount;
import dev.jackteng.figura.dto.UserRegistrationDto;
import dev.jackteng.figura.pojo.*;
import dev.jackteng.figura.service.AdminUserService;
import dev.jackteng.figura.service.RoleService;
import dev.jackteng.figura.service.UserService;
import dev.jackteng.figura.utils.JwtUtil;
import dev.jackteng.figura.utils.ThreadLocalUtil;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.util.StringUtils;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;



@RestController
@RequestMapping("/admin")
@Validated
public class AdminUserController {
    @Autowired
    private AdminUserService adminUserService;
    @Autowired
    private BCryptPasswordEncoder bCryptPasswordEncoder;
    @Autowired
    private StringRedisTemplate stringRedisTemplate;
    @Autowired
    private RoleService roleService;
    @Autowired
    private ObjectMapper objectMapper;

    //管理員 註冊
    @PostMapping("/register")
//    public Result register(@Pattern(regexp = "^\\S{5,16}$") String username, @Pattern(regexp = "^\\S{5,16}$") String password) {
    public Result register(@RequestBody @Valid UserRegistrationDto user) {
        //確認密碼跟確認密碼是否一致
        if (!user.getPassword().equals(user.getRePassword())) {
            return Result.error("確認密碼不相同");
        }
//        System.out.println(user.toString());
        //查詢用戶
        User u = adminUserService.findByUserName(user.getUsername());
        if (u == null) {
            //註冊
            adminUserService.register(user);
            return Result.success();
        } else {
            return Result.error("用戶名已被占用");
        }
    }

    //管理員 登入
    @Autowired
    private dev.jackteng.figura.service.AdminSessionService sessions;

    @PostMapping("/login")
    public Result<User> login(@RequestParam String username, @RequestParam String password,
                             @RequestParam String deviceId, @RequestParam(defaultValue = "false") boolean rememberMe,
                             HttpServletResponse response) {
        return Result.success(sessions.login(username, password, deviceId, rememberMe, response));
    }

    @PostMapping("/logout")
    public Result<Void> logout(HttpServletRequest request, HttpServletResponse response) {
        sessions.logout(JwtUtil.extractCookieToken(request, "refreshToken"), response);
        return Result.success();
    }

    //查詢所有帳號
    @PostMapping("/account")
    public Result<PageBean<User>> findAllAccount(
            @RequestBody UserAccount userAccount
    ) {
//        System.out.println("pageNum:::" + userAccount.getPageNum());
//        System.out.println("pageSize:::" + userAccount.getPageSize());
//        System.out.println("username:::" + userAccount.getUsername());
//        System.out.println("role:::" + userAccount.getRole());
//        System.out.println("isStop:::" + userAccount.getIsStop());
        PageBean<User> userlist = adminUserService.findAllUser(userAccount.getPageNum(), userAccount.getPageSize(), userAccount.getUsername(), userAccount.getRole(), userAccount.getIsStop());
//        System.out.println(userlist);
        return Result.success(userlist);
    }

    //查詢後台用戶所有資訊
    @GetMapping("/user/{username}")
    public Result<User> findAllByUserName(@PathVariable String username, HttpServletRequest request, HttpServletResponse response) {
        return Result.success(adminUserService.findAllByUserName(username));
    }

    //查詢所有角色 - 搜尋帳號用
    @GetMapping("/role")
    public Result<List<Role>> getAllRole() {
        return Result.success(roleService.findAllRole());
    }

    //--- "停用"/"啟用" 帳號---
    @PatchMapping("/stopAccount")
    public Result stopAccount(@NotEmpty(message = "操作錯誤") String username, @AuthenticationPrincipal CustomUserDetails currentUser) {
        User user = adminUserService.findByUserName(username);
        if (user.getIsStop() == null) {
            return Result.error("操作錯誤");
        } else if ("T".equals(user.getIsStop())) {
            adminUserService.stopAccount(user.getId(), "F");
        } else if ("F".equals(user.getIsStop())) {
            adminUserService.stopAccount(user.getId(), "T");
        }
        return Result.success();
    }

    //更新用戶訊息
    @PutMapping("/user/update")
    public Result update(@RequestBody @Validated User user) throws JsonProcessingException {
        adminUserService.update(user);
        User updatedUser = adminUserService.findByUserName(user.getUsername());

        String userJson = objectMapper.writeValueAsString(updatedUser);
        String redisKey = "userInfo:" + updatedUser.getId();
        stringRedisTemplate.opsForValue().set(redisKey, userJson, 12, TimeUnit.HOURS);
        return Result.success();
    }

}
