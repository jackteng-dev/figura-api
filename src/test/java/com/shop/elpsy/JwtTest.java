package com.shop.elpsy;

import com.auth0.jwt.JWT;
import com.auth0.jwt.algorithms.Algorithm;
import com.shop.elpsy.utils.JwtUtil;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletResponse;
import java.time.Instant;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;

class JwtTest {
    private final String secret = "unit-test-key-only-01234567890123456789";
    private final JwtUtil jwt = new JwtUtil(secret, true);
    @Test void rejectsShortDeploymentKey() { assertThrows(IllegalArgumentException.class, () -> new JwtUtil("short", true)); }
    @Test void accessAndRefreshHaveDifferentPurposeAndLifetime() {
        String access = jwt.genAccessToken(Map.of("id", 1)), refresh = jwt.genRefreshToken(Map.of("id", 1));
        assertTrue(jwt.validate(access, "access")); assertFalse(jwt.validate(refresh, "access"));
        assertTrue(jwt.validate(refresh, "refresh")); assertFalse(jwt.validate(access, "refresh"));
        assertEquals(900, JWT.decode(access).getExpiresAtAsInstant().getEpochSecond() - JWT.decode(access).getIssuedAtAsInstant().getEpochSecond());
        assertEquals(604800, JWT.decode(refresh).getExpiresAtAsInstant().getEpochSecond() - JWT.decode(refresh).getIssuedAtAsInstant().getEpochSecond());
    }
    @Test void rejectsDifferentKeyAndMalformedToken() {
        assertFalse(new JwtUtil(secret + "other", true).validate(jwt.genAccessToken(Map.of()), "access"));
        assertFalse(jwt.validate("bad", "access")); assertFalse(jwt.validate(null, "access"));
    }
    @Test void rejectsExpiredToken() {
        String expired = JWT.create().withIssuer("elpsy-shop").withClaim("claims", Map.of("token_type", "access"))
                .withExpiresAt(Instant.now().minusSeconds(1)).sign(Algorithm.HMAC256(secret));
        assertFalse(jwt.validate(expired, "access"));
    }
    @Test void cookiesAreHttpOnlyStrictAndSecureIncludingDeletion() {
        var response = new MockHttpServletResponse(); jwt.setJwtCookie(response, "accessToken", "test", 900); jwt.clearJwtCookie(response, "refreshToken");
        for (String cookie : response.getHeaders("Set-Cookie")) { assertTrue(cookie.contains("HttpOnly")); assertTrue(cookie.contains("Secure")); assertTrue(cookie.contains("SameSite=Strict")); }
        assertTrue(response.getHeaders("Set-Cookie").stream().anyMatch(c -> c.contains("Max-Age=0")));
    }
    @Test void loopbackSessionCookieHasNoPersistentMaxAge() {
        var response = new MockHttpServletResponse(); new JwtUtil(secret, false).setJwtCookie(response, "accessToken", "test", -1);
        assertFalse(response.getHeader("Set-Cookie").contains("Max-Age")); assertFalse(response.getHeader("Set-Cookie").contains("Secure"));
    }
}
