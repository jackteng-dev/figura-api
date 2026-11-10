package dev.jackteng.figura.pojo;

import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.userdetails.UserDetails;

import java.util.Collection;

public class CustomUserDetails implements UserDetails {

    private String username;
    private Collection<? extends GrantedAuthority> authorities;
    private Integer id;

    // 构造函数
    public CustomUserDetails(String username ,Integer id,Collection<? extends GrantedAuthority> authorities) {
        this.username = username;
        this.authorities = authorities;
        this.id = id;
    }

    // 实现 UserDetails 接口的方法
    @Override
    public Collection<? extends GrantedAuthority> getAuthorities() {
        return authorities;
    }

    @Override
    public String getPassword() {
        // JWT 认证不需要密码，所以返回 null
        return null;
    }

    @Override
    public String getUsername() {
        return username;
    }

    public Integer getId() {
        return id;
    }

    // 其他方法根据需要实现，这里简化为直接返回 true
    @Override
    public boolean isAccountNonExpired() { return true; }
    @Override
    public boolean isAccountNonLocked() { return true; }
    @Override
    public boolean isCredentialsNonExpired() { return true; }
    @Override
    public boolean isEnabled() { return true; }
}
