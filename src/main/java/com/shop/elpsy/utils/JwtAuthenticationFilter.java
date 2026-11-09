package com.shop.elpsy.utils;

import com.shop.elpsy.pojo.CustomUserDetails;
import com.shop.elpsy.pojo.CustomerPrincipal;
import com.shop.elpsy.service.CustomerSessionService;
import java.util.List;
import com.shop.elpsy.pojo.User;
import com.shop.elpsy.service.AdminSessionService;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.util.AntPathMatcher;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.server.ResponseStatusException;
import java.io.IOException;
import java.util.Set;

@Component
public class JwtAuthenticationFilter extends OncePerRequestFilter {
    private static final Set<String> PUBLIC = Set.of("/api/admin/login", "/api/admin/logout", "/api/auth/refresh", "/api/auth/csrf",
            "/api/customer/auth/register", "/api/customer/auth/login", "/api/customer/auth/refresh", "/api/customer/auth/logout");
    private final AdminSessionService sessions;
    private final CustomerSessionService customers;
    private final AntPathMatcher matcher = new AntPathMatcher();
    public JwtAuthenticationFilter(AdminSessionService sessions, CustomerSessionService customers) { this.sessions = sessions; this.customers = customers; }
    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain) throws ServletException, IOException {
        String path = request.getServletPath();
        if ("GET".equals(request.getMethod()) && (path.equals("/api/catalog/preorders") || matcher.match("/api/catalog/preorders/*",path) || path.equals("/api/catalog/products") || matcher.match("/api/catalog/products/*",path) || matcher.match("/api/catalog/products/*/image/*",path))) {
            chain.doFilter(request,response); return;
        }
        if ("POST".equals(request.getMethod()) && (path.equals("/api/payments/mock/callback") || path.equals("/api/preorder-payments/mock/callback"))) { chain.doFilter(request,response); return; }
        if (PUBLIC.contains(path)) { chain.doFilter(request, response); return; }
        if (path.equals("/api/user") || path.startsWith("/api/user/")) { error(response, 410, "舊會員 API 已停用"); return; }
        if (path.equals("/api/customer") || path.startsWith("/api/customer/")) {
            com.shop.elpsy.pojo.Customer customer;
            try { customer = customers.authenticate(JwtUtil.extractCookieToken(request, CustomerSessionService.ACCESS_COOKIE)); }
            catch (ResponseStatusException e) { error(response, 401, "顧客登入狀態無效"); return; }
            catch (RuntimeException e) { error(response, 503, "登入服務暫時無法使用"); return; }
            String method=request.getMethod();
            boolean allowed=customerRouteAllowed(path,method);
            if (!allowed) {
                error(response, 403, "無使用權限"); return;
            }
            var authentication = new UsernamePasswordAuthenticationToken(new CustomerPrincipal(customer.getId()), null, List.of());
            SecurityContextHolder.getContext().setAuthentication(authentication);
            try { chain.doFilter(request,response); } finally { SecurityContextHolder.clearContext(); }
            return;
        }
        User user;
        try { user = sessions.authenticate(JwtUtil.extractCookieToken(request, "accessToken")); }
        catch (ResponseStatusException e) { error(response, 401, "登入狀態無效"); return; }
        catch (RuntimeException e) { error(response, 503, "登入服務暫時無法使用"); return; }
        if ((path.startsWith("/api/role/") && !"GET".equals(request.getMethod()))
                || Set.of("/api/admin/register","/api/admin/stopAccount","/api/admin/user/update").contains(path)) {
            error(response,410,"舊存取管理寫入已停用，請使用新版管理 API"); return;
        }
        boolean authorized = ("/api/auth/me".equals(path) && "GET".equals(request.getMethod())) || user.getPermissions().stream().anyMatch(p ->
                p.getUrl() != null && p.getMethod() != null && matcher.match(p.getUrl(), path) && p.getMethod().equalsIgnoreCase(request.getMethod()));
        if (!authorized) { error(response, 403, "無使用權限"); return; }
        var authorities = user.getPermissions().stream().map(p -> new SimpleGrantedAuthority(p.getName())).distinct().toList();
        var principal = new CustomUserDetails(user.getUsername(), user.getId(), authorities);
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(principal, null, authorities));
        try { chain.doFilter(request, response); } finally { SecurityContextHolder.clearContext(); }
    }
    public static boolean customerRouteAllowed(String path,String method) {
        var matcher=new AntPathMatcher();
        return (path.equals("/api/customer/me")&&(method.equals("GET")||method.equals("PATCH")))
                    || (path.equals("/api/customer/cart")&&method.equals("GET"))
                    || (matcher.match("/api/customer/cart/items/*",path)&&(method.equals("PUT")||method.equals("DELETE")))
                    || (path.equals("/api/customer/orders")&&(method.equals("GET")||method.equals("POST")))
                    || (path.equals("/api/customer/preorders")&&(method.equals("GET")||method.equals("POST")))
                    || (matcher.match("/api/customer/preorders/*",path)&&method.equals("GET"))
                    || (matcher.match("/api/customer/preorders/*/cancel",path)&&method.equals("POST"))
                    || (matcher.match("/api/customer/preorders/*/mock-payments",path)&&(method.equals("GET")||method.equals("POST")))
                    || (matcher.match("/api/customer/preorder-payments/*/simulate",path)&&method.equals("POST"))
                    || (matcher.match("/api/customer/orders/*",path)&&method.equals("GET"))
                    || (matcher.match("/api/customer/orders/*/mock-return",path)&&(method.equals("GET")||method.equals("POST")))
                    || (matcher.match("/api/customer/orders/*/cancel",path)&&method.equals("POST"))
                    || (matcher.match("/api/customer/orders/*/mock-payments",path)&&(method.equals("GET")||method.equals("POST")))
                    || (matcher.match("/api/customer/mock-payments/*/simulate",path)&&method.equals("POST"))
                    || (path.equals("/api/customer/addresses")&&method.equals("GET"))
                    || (matcher.match("/api/customer/addresses/*",path)&&(method.equals("PUT")||method.equals("DELETE")));
    }
    private void error(HttpServletResponse response, int status, String message) throws IOException {
        response.setStatus(status); response.setContentType("application/json"); response.setCharacterEncoding("UTF-8");
        response.getWriter().write("{\"code\":1,\"message\":\"" + message + "\",\"data\":null}");
    }
}
