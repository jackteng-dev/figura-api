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

    /** 雙因素第二步：{challenge, code}。 */
    public record MfaLogin(@jakarta.validation.constraints.NotBlank String challenge, @jakarta.validation.constraints.NotBlank String code) {}
    @PostMapping("/login/mfa")
    public Result<User> loginMfa(@RequestBody @jakarta.validation.Valid MfaLogin in, HttpServletResponse response) {
        return Result.success(sessions.completeMfa(in.challenge(), in.code(), response));
    }
    @Autowired private dev.jackteng.figura.service.StaffMfaService mfa;
    /** 強制綁定：以密碼登入取得的綁定 token 產生密鑰，再以驗證碼完成綁定（之後才發 session）。 */
    public record MfaSetupToken(@jakarta.validation.constraints.NotBlank String setupToken) {}
    public record MfaEnroll(@jakarta.validation.constraints.NotBlank String setupToken, @jakarta.validation.constraints.NotBlank String code) {}
    @PostMapping("/login/mfa/setup") public Result<dev.jackteng.figura.service.StaffMfaService.Setup> loginMfaSetup(@RequestBody @jakarta.validation.Valid MfaSetupToken in) { return Result.success(mfa.setup(in.setupToken())); }
    @PostMapping("/login/mfa/enroll") public Result<User> loginMfaEnroll(@RequestBody @jakarta.validation.Valid MfaEnroll in, HttpServletResponse response) { return Result.success(sessions.completeEnrollment(in.setupToken(), in.code(), response)); }
    public record MfaCode(@jakarta.validation.constraints.NotBlank String code) {}
    @GetMapping("/mfa") public Result<dev.jackteng.figura.service.StaffMfaService.Status> mfaStatus(@AuthenticationPrincipal CustomUserDetails me) { return Result.success(mfa.status(me.getId())); }
    @PostMapping("/mfa/backup-codes") public Result<java.util.List<String>> mfaBackupCodes(@AuthenticationPrincipal CustomUserDetails me, @RequestBody @jakarta.validation.Valid MfaCode in) { return Result.success(mfa.regenerateBackupCodes(me.getId(), in.code())); }
    public record MfaReset(@jakarta.validation.constraints.NotBlank @jakarta.validation.constraints.Pattern(regexp = "[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}") String requestId,
                           @jakarta.validation.constraints.NotBlank @jakarta.validation.constraints.Size(max = 500) String note) {}
    public record MfaResetResult(int staffId, boolean replayed) {}
    /** 管理員重設其他員工的雙因素（權限 access.mfa.reset）；對方 session 立即失效，下次登入重新綁定。 */
    @PostMapping("/access/staff/{id}/mfa/reset") public Result<MfaResetResult> mfaReset(@PathVariable int id, @RequestBody @jakarta.validation.Valid MfaReset in, @AuthenticationPrincipal CustomUserDetails me) {
        return Result.success(new MfaResetResult(id, mfa.reset(id, in.requestId(), in.note(), me)));
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
