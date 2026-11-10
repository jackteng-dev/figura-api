package dev.jackteng.figura;

import dev.jackteng.figura.pojo.Permission;
import dev.jackteng.figura.pojo.User;
import dev.jackteng.figura.service.AdminSessionService;
import dev.jackteng.figura.utils.JwtAuthenticationFilter;
import dev.jackteng.figura.utils.SecurityConfig;
import jakarta.servlet.Filter;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Bean;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Profile;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.context.annotation.Import;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.junit.jupiter.SpringExtension;
import org.springframework.test.context.web.WebAppConfiguration;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.context.WebApplicationContext;
import org.springframework.web.servlet.config.annotation.EnableWebMvc;
import java.util.List;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@ExtendWith(SpringExtension.class)
@WebAppConfiguration
@ActiveProfiles("security-chain-test")
@ContextConfiguration(classes = SecurityChainTest.TestConfig.class)
@TestPropertySource(properties = "auth.cookie-secure=false")
class SecurityChainTest {
    @TestConfiguration @EnableWebMvc @EnableWebSecurity
    @Import({SecurityConfig.class, JwtAuthenticationFilter.class, Probe.class})
    static class TestConfig {
        @Bean dev.jackteng.figura.service.CustomerSessionService customers() { return mock(dev.jackteng.figura.service.CustomerSessionService.class); }
        @Bean AdminSessionService sessions() { return mock(AdminSessionService.class); }
    }
    @RestController @Profile("security-chain-test") static class Probe {
        @GetMapping("/api/auth/csrf") Map<String,String> csrf(CsrfToken token) { return Map.of("token", token.getToken()); }
        @GetMapping("/api/auth/me") Map<String,String> me() { return Map.of("name", "staff"); }
        @PostMapping("/api/auth/refresh") Map<String,String> refresh() { return Map.of("status", "ok"); }
    }
    @Autowired WebApplicationContext context;
    @Autowired AdminSessionService sessions;
    private MockMvc mvc;
    @BeforeEach void setup() {
        reset(sessions);
        var permission = new Permission(); permission.setName("identity"); permission.setUrl("/api/auth/me"); permission.setMethod("GET");
        var user = new User(); user.setId(1); user.setUsername("staff"); user.setPermissions(List.of(permission));
        when(sessions.authenticate("access")).thenReturn(user);
        mvc = MockMvcBuilders.webAppContextSetup(context).addFilters(context.getBean("springSecurityFilterChain", Filter.class)).build();
    }
    @Test void authenticatedIdentityDoesNotClearCsrfOrCreateHttpSession() throws Exception {
        var csrf = mvc.perform(get("/api/auth/csrf").servletPath("/api/auth/csrf")).andExpect(status().isOk()).andReturn().getResponse().getCookie("XSRF-TOKEN");
        assertNotNull(csrf); assertTrue(csrf.isHttpOnly());
        var result = mvc.perform(get("/api/auth/me").servletPath("/api/auth/me").cookie(csrf, new Cookie("accessToken", "access")))
                .andExpect(status().isOk()).andReturn();
        assertNull(result.getResponse().getCookie("XSRF-TOKEN"));
        assertNull(result.getResponse().getCookie("JSESSIONID"));
        assertNull(result.getRequest().getSession(false));
        mvc.perform(post("/api/auth/me").servletPath("/api/auth/me").cookie(csrf, new Cookie("accessToken", "access"))
                .header("X-XSRF-TOKEN", csrf.getValue())).andExpect(status().isForbidden());
        mvc.perform(post("/api/auth/refresh").servletPath("/api/auth/refresh").cookie(csrf).header("X-XSRF-TOKEN", csrf.getValue())).andExpect(status().isOk());
    }
    @Test void publicRefreshStillRejectsMissingOrMismatchedCsrf() throws Exception {
        mvc.perform(post("/api/auth/refresh").servletPath("/api/auth/refresh"))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.errorCode").value("CSRF_INVALID"));
        var csrf = mvc.perform(get("/api/auth/csrf").servletPath("/api/auth/csrf")).andReturn().getResponse().getCookie("XSRF-TOKEN");
        mvc.perform(post("/api/auth/refresh").servletPath("/api/auth/refresh").cookie(csrf).header("X-XSRF-TOKEN", "incorrect"))
                .andExpect(status().isForbidden());
    }
}
