package com.shop.elpsy;

import com.shop.elpsy.pojo.Permission;
import com.shop.elpsy.pojo.User;
import com.shop.elpsy.service.AdminSessionService;
import com.shop.elpsy.utils.JwtAuthenticationFilter;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.server.ResponseStatusException;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class JwtAuthenticationFilterTest {
    private final AdminSessionService sessions = mock(AdminSessionService.class);
    private final JwtAuthenticationFilter filter = new JwtAuthenticationFilter(sessions, mock(com.shop.elpsy.service.CustomerSessionService.class));
    private MockHttpServletRequest request(String method, String path) {
        var r = new MockHttpServletRequest(method, "/ELPSY" + path); r.setContextPath("/ELPSY"); r.setServletPath(path); r.setCookies(new Cookie("accessToken", "token")); return r;
    }
    private User user(String url, String method) {
        var p = new Permission(); p.setName("測試授權"); p.setUrl(url); p.setMethod(method);
        var u = new User(); u.setUsername("staff"); u.setId(1); u.setPermissions(List.of(p)); return u;
    }
    @Test void exactPermissionAllowsAndClearsContext() throws Exception {
        when(sessions.authenticate("token")).thenReturn(user("/api/admin/role", "GET"));
        var response = new MockHttpServletResponse(); var chain = new MockFilterChain(); filter.doFilter(request("GET", "/api/admin/role"), response, chain);
        assertNotNull(chain.getRequest()); assertEquals(200, response.getStatus()); assertNull(SecurityContextHolder.getContext().getAuthentication());
    }
    @Test void sameUrlWrongMethodIsForbidden() throws Exception {
        when(sessions.authenticate("token")).thenReturn(user("/api/admin/role", "GET")); var response = new MockHttpServletResponse();
        var chain = new MockFilterChain(); filter.doFilter(request("POST", "/api/admin/role"), response, chain);
        assertEquals(403, response.getStatus()); assertNull(chain.getRequest());
    }
    @Test void unrelatedPermissionDoesNotAuthorize() throws Exception {
        when(sessions.authenticate("token")).thenReturn(user("/api/auth/me", "GET")); var response = new MockHttpServletResponse();
        filter.doFilter(request("GET", "/api/admin/role"), response, new MockFilterChain()); assertEquals(403, response.getStatus());
    }
    @Test void missingOrRevokedSessionIsUnauthorized() throws Exception {
        when(sessions.authenticate("token")).thenThrow(new ResponseStatusException(HttpStatus.UNAUTHORIZED)); var response = new MockHttpServletResponse();
        filter.doFilter(request("GET", "/api/auth/me"), response, new MockFilterChain()); assertEquals(401, response.getStatus());
    }
    @Test void serviceFailureDoesNotGrantAccess() throws Exception {
        when(sessions.authenticate("token")).thenThrow(new IllegalStateException()); var response = new MockHttpServletResponse();
        filter.doFilter(request("GET", "/api/admin/role"), response, new MockFilterChain()); assertEquals(503, response.getStatus());
    }
    @Test void authenticatedStaffCanReadOwnIdentity() throws Exception {
        when(sessions.authenticate("token")).thenReturn(user("/api/other", "GET")); var chain = new MockFilterChain();
        filter.doFilter(request("GET", "/api/auth/me"), new MockHttpServletResponse(), chain); assertNotNull(chain.getRequest());
    }
    @Test void csrfEndpointDoesNotRequireSession() throws Exception {
        var chain = new MockFilterChain(); filter.doFilter(request("GET", "/api/auth/csrf"), new MockHttpServletResponse(), chain);
        assertNotNull(chain.getRequest()); verifyNoInteractions(sessions);
    }
}
