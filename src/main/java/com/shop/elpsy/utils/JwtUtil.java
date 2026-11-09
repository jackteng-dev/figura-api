package com.shop.elpsy.utils;

import com.auth0.jwt.JWT;
import com.auth0.jwt.algorithms.Algorithm;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseCookie;
import org.springframework.stereotype.Component;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;

@Component
public class JwtUtil {
    public static final Duration ACCESS_LIFETIME = Duration.ofMinutes(15);
    public static final Duration REFRESH_LIFETIME = Duration.ofDays(7);
    private final Algorithm algorithm;
    private final boolean cookieSecure;
    public JwtUtil(@Value("${auth.jwt-secret}") String secret, @Value("${auth.cookie-secure:true}") boolean secure) {
        if (secret == null || secret.getBytes(java.nio.charset.StandardCharsets.UTF_8).length < 32)
            throw new IllegalArgumentException("JWT_SECRET 必須提供至少 32 bytes 的私有隨機金鑰");
        algorithm = Algorithm.HMAC256(secret); cookieSecure = secure;
    }
    public String genAccessToken(Map<String, Object> claims) { return generate(claims, "access", ACCESS_LIFETIME); }
    public String genRefreshToken(Map<String, Object> claims) { return generate(claims, "refresh", REFRESH_LIFETIME); }
    private String generate(Map<String, Object> claims, String type, Duration lifetime) {
        var payload = new HashMap<>(claims); payload.put("token_type", type);
        return JWT.create().withIssuer("elpsy-shop").withClaim("claims", payload).withIssuedAt(Instant.now())
                .withExpiresAt(Instant.now().plus(lifetime)).sign(algorithm);
    }
    public Map<String, Object> parseToken(String token) {
        return JWT.require(algorithm).withIssuer("elpsy-shop").build().verify(token).getClaim("claims").asMap();
    }
    public boolean validate(String token, String type) {
        try { return type.equals(parseToken(token).get("token_type")); } catch (RuntimeException e) { return false; }
    }
    public void setJwtCookie(HttpServletResponse response, String name, String value, int age) {
        var cookie = ResponseCookie.from(name, value).httpOnly(true).secure(cookieSecure).sameSite("Strict").path("/");
        if (age >= 0) cookie.maxAge(age);
        response.addHeader(HttpHeaders.SET_COOKIE, cookie.build().toString());
    }
    public void clearJwtCookie(HttpServletResponse response, String name) { setJwtCookie(response, name, "", 0); }
    public static String extractCookieToken(HttpServletRequest request, String name) {
        if (request.getCookies() != null) for (Cookie cookie : request.getCookies()) if (name.equals(cookie.getName())) return cookie.getValue();
        return null;
    }
}
