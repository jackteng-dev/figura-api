package com.shop.elpsy;

import com.shop.elpsy.pojo.Permission;
import com.shop.elpsy.pojo.User;
import com.shop.elpsy.service.*;
import com.shop.elpsy.utils.JwtUtil;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.web.server.ResponseStatusException;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class AdminSessionServiceTest {
    private final AdminUserService users = mock(AdminUserService.class);
    private final RoleService roles = mock(RoleService.class);
    private final StringRedisTemplate redis = mock(StringRedisTemplate.class);
    private final BCryptPasswordEncoder passwords = new BCryptPasswordEncoder();
    private final JwtUtil jwt = new JwtUtil("unit-test-secret-only-01234567890123456789", false);
    private final AdminSessionService sessions = new AdminSessionService(users, roles, passwords, redis, jwt);
    private final String device = UUID.randomUUID().toString();
    private User user() { var u = new User(); u.setId(1); u.setUsername("staff"); u.setIsStop("F"); u.setPassword(passwords.encode("test-password")); return u; }
    private Map<String,Object> claims() { return Map.of("id",1,"username","staff","deviceId",device,"jti","session-id","rememberMe",false,"realm","staff","authVersion",0); }
    @Test void disabledUserCannotCreateSession() {
        var u = user(); u.setIsStop("T"); when(users.findByUserName("staff")).thenReturn(u);
        assertThrows(ResponseStatusException.class, () -> sessions.login("staff","test-password",device,false,new MockHttpServletResponse())); verifyNoInteractions(redis);
    }
    @Test void wrongPasswordCannotCreateSession() {
        when(users.findByUserName("staff")).thenReturn(user());
        assertThrows(ResponseStatusException.class, () -> sessions.login("staff","wrong",device,false,new MockHttpServletResponse())); verifyNoInteractions(redis);
    }
    @Test void noBackendPermissionsCannotLogin() {
        when(users.findByUserName("staff")).thenReturn(user()); when(roles.getUserBackendPermission(1)).thenReturn(List.of());
        assertThrows(ResponseStatusException.class, () -> sessions.login("staff","test-password",device,false,new MockHttpServletResponse())); verifyNoInteractions(redis);
    }
    @Test void refreshTokenCannotActAsAccessToken() { assertThrows(ResponseStatusException.class, () -> sessions.authenticate(jwt.genRefreshToken(claims()))); verifyNoInteractions(redis); }
    @Test void deletedSessionRejectsPreviouslySignedAccess() {
        ValueOperations operations = mock(ValueOperations.class); when(redis.opsForValue()).thenReturn(operations);
        assertThrows(ResponseStatusException.class, () -> sessions.authenticate(jwt.genAccessToken(claims()))); verifyNoInteractions(users);
    }
    @Test void stoppedAccountImmediatelyRejectsExistingSession() {
        ValueOperations operations = mock(ValueOperations.class); when(redis.opsForValue()).thenReturn(operations); when(operations.get(anyString())).thenReturn("session-id");
        var u = user(); u.setIsStop("T"); when(users.findByUserName("staff")).thenReturn(u);
        assertThrows(ResponseStatusException.class, () -> sessions.authenticate(jwt.genAccessToken(claims())));
    }
    @Test void revokedPermissionsImmediatelyRejectExistingSession() {
        ValueOperations operations = mock(ValueOperations.class); when(redis.opsForValue()).thenReturn(operations); when(operations.get(anyString())).thenReturn("session-id");
        when(users.findByUserName("staff")).thenReturn(user()); when(roles.getUserBackendPermission(1)).thenReturn(List.of());
        assertThrows(ResponseStatusException.class, () -> sessions.authenticate(jwt.genAccessToken(claims())));
    }
    @Test void reenablingAccountDoesNotReviveOldAccessOrRefresh() {
        ValueOperations operations=mock(ValueOperations.class); when(redis.opsForValue()).thenReturn(operations);when(operations.get(anyString())).thenReturn("session-id");
        var u=user();u.setAuthVersion(2);when(users.findByUserName("staff")).thenReturn(u);
        assertThrows(ResponseStatusException.class,()->sessions.authenticate(jwt.genAccessToken(claims())));
        assertThrows(ResponseStatusException.class,()->sessions.refresh(jwt.genRefreshToken(claims()),new MockHttpServletResponse()));
        verify(redis,never()).execute(any(org.springframework.data.redis.core.script.RedisScript.class),anyList(),any(Object[].class));
    }
    @Test void legacyAccessWithoutAuthVersionRequiresFreshLogin() {
        ValueOperations operations=mock(ValueOperations.class);when(redis.opsForValue()).thenReturn(operations);when(operations.get(anyString())).thenReturn("session-id");
        when(users.findByUserName("staff")).thenReturn(user());var old=new java.util.HashMap<String,Object>(claims());old.remove("authVersion");
        assertThrows(ResponseStatusException.class,()->sessions.authenticate(jwt.genAccessToken(old)));
        assertThrows(ResponseStatusException.class,()->sessions.refresh(jwt.genRefreshToken(old),new MockHttpServletResponse()));
    }
    @Test void malformedLogoutStillDeletesBothCookies() {
        var response = new MockHttpServletResponse(); sessions.logout("malformed", response);
        assertEquals(2,response.getHeaders("Set-Cookie").size()); assertTrue(response.getHeaders("Set-Cookie").stream().allMatch(c -> c.contains("Max-Age=0")));
    }
}
