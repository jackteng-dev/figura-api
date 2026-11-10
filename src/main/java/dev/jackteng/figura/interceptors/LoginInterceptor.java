package dev.jackteng.figura.interceptors;

import dev.jackteng.figura.utils.JwtUtil;
import dev.jackteng.figura.utils.ThreadLocalUtil;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

import java.util.Map;

@Component
public class LoginInterceptor implements HandlerInterceptor {
    @Autowired
    private JwtUtil jwtUtil;

    @Autowired
    private StringRedisTemplate stringRedisTemplate;
    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) throws Exception {
        //令牌驗證
        String token = request.getHeader("Authorization");
        //驗證token
        try {
            Map<String, Object> claims = jwtUtil.parseToken(token);
            String userId = String.valueOf(claims.get("id"));
            //從redis獲取相同的token
            ValueOperations<String, String> operations = stringRedisTemplate.opsForValue();
            String tokenKey = "token:" + userId;
            String redistoken = operations.get(tokenKey);
            if(redistoken == null){
                //redistoken已經失效
                throw new RuntimeException();
            }
            //使用threadlocal放置用戶訊息
            ThreadLocalUtil.set(claims);
            //成功放行
            return true;
        } catch (Exception e) {
            response.setStatus(401);
            //不放行
            return false;
        }
    }

    @Override
    public void afterCompletion(HttpServletRequest request, HttpServletResponse response, Object handler, Exception ex) throws Exception {
        //清空threadlocal中的數據
        ThreadLocalUtil.remove();
    }
}
