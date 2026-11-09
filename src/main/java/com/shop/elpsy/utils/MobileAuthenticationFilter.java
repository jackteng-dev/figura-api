package com.shop.elpsy.utils;

import com.shop.elpsy.pojo.CustomerPrincipal;
import com.shop.elpsy.service.CustomerSessionService;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.server.ResponseStatusException;
import java.io.IOException;
import java.util.List;
import java.util.Set;

// Registered only inside the native chain, never as a servlet or website filter.
public final class MobileAuthenticationFilter extends OncePerRequestFilter {
    private static final Set<String> PUBLIC=Set.of("/api/mobile/customer/auth/register","/api/mobile/customer/auth/login",
            "/api/mobile/customer/auth/refresh","/api/mobile/customer/auth/logout");
    private final CustomerSessionService sessions;
    public MobileAuthenticationFilter(CustomerSessionService sessions){this.sessions=sessions;}
    @Override protected void doFilterInternal(HttpServletRequest request,HttpServletResponse response,FilterChain chain) throws ServletException,IOException {
        response.setHeader("Cache-Control","no-store");
        String path=request.getServletPath(),method=request.getMethod();
        if("POST".equals(method)&&PUBLIC.contains(path)){chain.doFilter(request,response);return;}
        String header=request.getHeader("Authorization");
        if(header==null || !header.startsWith("Bearer ") || header.length()>4096){error(response,401);return;}
        try {
            var customer=sessions.mobileAuthenticate(header.substring(7));
            if(!JwtAuthenticationFilter.customerRouteAllowed(path.replaceFirst("^/api/mobile/customer","/api/customer"),method)){error(response,403);return;}
            SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(new CustomerPrincipal(customer.getId()),null,List.of()));
        } catch(ResponseStatusException e){error(response,401);return;}
          catch(RuntimeException e){error(response,503);return;}
        try {chain.doFilter(request,response);} finally {SecurityContextHolder.clearContext();}
    }
    private void error(HttpServletResponse response,int status)throws IOException {
        response.setStatus(status);response.setContentType("application/json");response.setCharacterEncoding("UTF-8");
        response.getWriter().write("{\"code\":1,\"message\":\""+(status==503?"登入服務暫時無法使用":status==403?"無使用權限":"App 登入狀態無效")+"\",\"data\":null}");
    }
}
