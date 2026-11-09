package com.shop.elpsy.utils;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.web.context.NullSecurityContextRepository;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.security.web.csrf.CookieCsrfTokenRepository;
import org.springframework.security.web.csrf.CsrfTokenRequestAttributeHandler;

@Configuration
public class SecurityConfig {
    @Bean @org.springframework.core.annotation.Order(1)
    public SecurityFilterChain mobileChain(HttpSecurity http,com.shop.elpsy.service.CustomerSessionService customers) throws Exception {
        // Cookie credentials are never read by this chain. Only this namespace is exempt from CSRF.
        http.securityMatcher("/api/mobile/customer","/api/mobile/customer/**")
                .csrf(AbstractHttpConfigurer::disable).formLogin(AbstractHttpConfigurer::disable)
                .httpBasic(AbstractHttpConfigurer::disable).logout(AbstractHttpConfigurer::disable)
                .requestCache(AbstractHttpConfigurer::disable).sessionManagement(AbstractHttpConfigurer::disable)
                .securityContext(context -> context.securityContextRepository(new NullSecurityContextRepository()))
                .addFilterBefore(new MobileAuthenticationFilter(customers),UsernamePasswordAuthenticationFilter.class)
                .authorizeHttpRequests(auth -> auth.requestMatchers(org.springframework.http.HttpMethod.POST,
                        "/api/mobile/customer/auth/register","/api/mobile/customer/auth/login",
                        "/api/mobile/customer/auth/refresh","/api/mobile/customer/auth/logout").permitAll()
                        .anyRequest().authenticated());
        return http.build();
    }
    @Bean public BCryptPasswordEncoder passwordEncoder() { return new BCryptPasswordEncoder(); }
    @Bean public FilterRegistrationBean<JwtAuthenticationFilter> disableServletRegistration(JwtAuthenticationFilter filter) {
        var registration = new FilterRegistrationBean<>(filter); registration.setEnabled(false); return registration;
    }
    @Bean
    public SecurityFilterChain filterChain(HttpSecurity http, JwtAuthenticationFilter filter, @Value("${auth.cookie-secure:true}") boolean secure) throws Exception {
        var csrf = new CookieCsrfTokenRepository();
        csrf.setCookieCustomizer(cookie -> cookie.httpOnly(true).secure(secure).sameSite("Strict").path("/"));
        http.formLogin(AbstractHttpConfigurer::disable).httpBasic(AbstractHttpConfigurer::disable).logout(AbstractHttpConfigurer::disable)
                .requestCache(AbstractHttpConfigurer::disable)
                // Cookie JWT 每次驗證；不使用 HttpSession，也不把每次請求當成新的 session 登入。
                .sessionManagement(AbstractHttpConfigurer::disable)
                .securityContext(context -> context.securityContextRepository(new NullSecurityContextRepository()))
                .csrf(config -> config.csrfTokenRepository(csrf).csrfTokenRequestHandler(new CsrfTokenRequestAttributeHandler()).ignoringRequestMatchers(request -> "POST".equals(request.getMethod()) && ("/api/payments/mock/callback".equals(request.getServletPath()) || "/api/preorder-payments/mock/callback".equals(request.getServletPath()))))
                .addFilterBefore(filter, UsernamePasswordAuthenticationFilter.class)
                .exceptionHandling(config -> config.accessDeniedHandler((request, response, error) -> {
                    response.setStatus(403); response.setContentType("application/json"); response.setCharacterEncoding("UTF-8");
                    response.getWriter().write("{\"code\":1,\"errorCode\":\"CSRF_INVALID\",\"message\":\"操作驗證失效\"}");
                }))
                .authorizeHttpRequests(auth -> auth.requestMatchers(org.springframework.http.HttpMethod.GET,"/api/catalog/preorders","/api/catalog/preorders/*","/api/catalog/products","/api/catalog/products/*","/api/catalog/products/*/image/*").permitAll()
                        .requestMatchers("/api/admin/login", "/api/admin/logout", "/api/auth/refresh", "/api/auth/csrf",
                        "/api/customer/auth/register", "/api/customer/auth/login", "/api/customer/auth/refresh", "/api/customer/auth/logout").permitAll()
                        .requestMatchers(org.springframework.http.HttpMethod.POST,"/api/payments/mock/callback","/api/preorder-payments/mock/callback").permitAll()
                        .anyRequest().authenticated());
        return http.build();
    }
}
