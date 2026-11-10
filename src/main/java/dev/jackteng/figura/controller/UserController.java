package dev.jackteng.figura.controller;

import dev.jackteng.figura.dto.UserRegistrationDto;
import dev.jackteng.figura.pojo.CustomUserDetails;
import dev.jackteng.figura.pojo.Result;
import dev.jackteng.figura.pojo.User;
import dev.jackteng.figura.service.UserService;
import dev.jackteng.figura.utils.JwtUtil;
import dev.jackteng.figura.utils.ThreadLocalUtil;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.util.StringUtils;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;

@RestController
@RequestMapping("/user")
@Validated
public class UserController {
    @Autowired
    private JwtUtil jwtUtil;

    @Autowired
    private UserService userService;
    @Autowired
    private BCryptPasswordEncoder bCryptPasswordEncoder;
    @Autowired
    private StringRedisTemplate stringRedisTemplate;

    //一般使用者 註冊
    @PostMapping("/register")
//    public Result register(@Pattern(regexp = "^\\S{5,16}$") String username, @Pattern(regexp = "^\\S{5,16}$") String password) {
    public Result register(@RequestBody @Valid UserRegistrationDto user) {
        //查詢用戶
        User u = userService.findByUserName(user.getUsername());
        if (u == null) {
            //註冊
            user.setRole("U");
            userService.register(user);
            return Result.success();
        } else {
            return Result.error("用戶名已被占用");
        }
    }

    //一般使用者 登入
    @PostMapping("/login")
    // 密码长度8-20位且至少包含大写字母、小写字母、数字或特殊符号中的任意三种
    public Result<String> login(@NotEmpty(message = "用戶名不能為空") String username, @NotEmpty(message = "密碼不能為空") String password) {
        String token;
        //查詢用戶
        User loginuser = userService.findByUserName(username);
        if (loginuser == null) {
            return Result.error("用戶名錯誤");
        }
        //判斷密碼
        if (bCryptPasswordEncoder.matches(password, loginuser.getPassword())) {
            if (!"U".equals(loginuser.getRole())) {
                return Result.error("管理員請至管理員後台登入");
            } else if ("T".equals(loginuser.getIsStop())) {
                return Result.error("此帳號已被停用，請聯絡系統管理員");
            } else {
                //登入成功
                Map<String, Object> claims = new HashMap<>();
                claims.put("id", loginuser.getId());
                claims.put("username", loginuser.getUsername());
                token = jwtUtil.genAccessToken(claims);
                //把token存到redis
                ValueOperations<String, String> operations = stringRedisTemplate.opsForValue();
                operations.set(token, token, 1, TimeUnit.HOURS);
                userService.updateLoginTime(loginuser.getId());
                return Result.success(token);
            }
        } else {
            return Result.error("密碼錯誤");
        }
    }

    //查詢用戶訊息
    @GetMapping("/userInfo")
    public Result<User> userInfo() {
        Map<String, Object> map = ThreadLocalUtil.get();
        String username = (String) map.get("username");
//        System.out.println(username);
        User user = userService.findByUserName(username);
        System.out.println(user.toString());
        return Result.success(user);
    }

    //更新用戶訊息
    @PutMapping("/update")
    public Result update(@RequestBody @Validated User user) {
//        System.out.println(user.toString());
        userService.update(user);
        return Result.success();
    }

    //更新密碼
    @PreAuthorize("hasAuthority('更新用戶密碼')")
    @PatchMapping("/updatePwd")
    public Result updatePwd(@RequestBody Map<String, String> params, @RequestHeader("Authorization") String token,@AuthenticationPrincipal CustomUserDetails currentUser) {
//        System.out.println("aaa");
        //1.較驗參數
        String oldPwd = params.get("old_pwd");
        String newPwd = params.get("new_pwd");
        String rePwd = params.get("re_pwd");
        if (!StringUtils.hasLength(oldPwd) || !StringUtils.hasLength(newPwd) || !StringUtils.hasLength(rePwd)) {
            return Result.error("缺少必要的參數");
        }
        //原密碼是否正確
        User loginUser = userService.findByUserName(currentUser.getUsername());
        if (!bCryptPasswordEncoder.matches(oldPwd, loginUser.getPassword())) {
            return Result.error("原密碼不正確");
        }
        //確認密碼跟確認密碼是否一致
        if (!rePwd.equals(newPwd)) {
            return Result.error("確認密碼不相同");
        }
        String pattern = "^(?![a-zA-Z]+$)(?![A-Z0-9]+$)(?![A-Z\\W_]+$)(?![a-z0-9]+$)(?![a-z\\W_]+$)(?![0-9\\W_]+$)[a-zA-Z0-9\\W_]{8,20}$";
        if (!newPwd.matches(pattern)) {
            return Result.error("密碼格式不正確");
        }
        userService.updatePwd(newPwd,currentUser.getId());
//        //刪除redis中對應的token
//        ValueOperations<String, String> operations = stringRedisTemplate.opsForValue();
//        String accessTokenKey = "accessToken:" + currentUser.getId();
//        String refreshTokenKey = "refreshToken:" + currentUser.getId();
//        operations.getOperations().delete(accessTokenKey);
//        operations.getOperations().delete(refreshTokenKey);
        return Result.success();
    }

}
