package com.bidtix.auth.controller;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import com.bidtix.config.metrics.Metrics;
import com.bidtix.auth.dtos.AuthTokens;
import com.bidtix.auth.infrastructure.AuthProperties;
import com.bidtix.auth.service.AuthService;
import com.bidtix.config.exceptions.BusinessException;
import com.bidtix.config.exceptions.ErrorCode;
import com.bidtix.config.exceptions.GlobalExceptionHandler;
import com.bidtix.user.domain.User;
import com.bidtix.user.domain.UserRole;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.time.Duration;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** HTTP layer only: AuthService is a mock, so these tests are about JSON, cookies and status codes. */
class AuthControllerTest {

    private static final String COOKIE_NAME = "refresh_token";

    private final AuthService authService = mock(AuthService.class);
    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        AuthProperties properties = new AuthProperties(Duration.ofDays(7),
                new AuthProperties.RefreshCookie(COOKIE_NAME, "/api/v1/auth", true, "Strict"));
        mvc = MockMvcBuilders
                .standaloneSetup(new AuthController(authService, new RefreshTokenCookies(properties)))
                .setControllerAdvice(new GlobalExceptionHandler(new Metrics(new SimpleMeterRegistry())))
                .build();
    }

    // ---------------------------------------------------------------- login

    @Test
    void login_returnsAccessTokenInBody_andRefreshTokenOnlyInAHardenedCookie() throws Exception {
        when(authService.login("alice@example.com", "password123"))
                .thenReturn(new AuthTokens("access-jwt", "raw-refresh"));

        mvc.perform(post("/api/v1/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"alice@example.com\",\"password\":\"password123\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.accessToken").value("access-jwt"))
                .andExpect(jsonPath("$.tokenType").value("Bearer"))
                .andExpect(jsonPath("$.expiresIn").value(300))
                .andExpect(content().string(not(containsString("raw-refresh"))))
                .andExpect(header().string(HttpHeaders.SET_COOKIE, containsString(COOKIE_NAME + "=raw-refresh")))
                .andExpect(header().string(HttpHeaders.SET_COOKIE, containsString("HttpOnly")))
                .andExpect(header().string(HttpHeaders.SET_COOKIE, containsString("Secure")))
                .andExpect(header().string(HttpHeaders.SET_COOKIE, containsString("SameSite=Strict")))
                .andExpect(header().string(HttpHeaders.SET_COOKIE, containsString("Path=/api/v1/auth")))
                .andExpect(header().string(HttpHeaders.SET_COOKIE, containsString("Max-Age=604800")));
    }

    @Test
    void login_wrongCredentials_is401_withTheGenericCode() throws Exception {
        when(authService.login("alice@example.com", "wrong")).thenThrow(new BadCredentialsException("x"));

        mvc.perform(post("/api/v1/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"alice@example.com\",\"password\":\"wrong\"}"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("INVALID_CREDENTIALS"))
                .andExpect(header().doesNotExist(HttpHeaders.SET_COOKIE));
    }

    @Test
    void login_blankFields_is400() throws Exception {
        mvc.perform(post("/api/v1/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"\",\"password\":\"\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST"));
    }

    // ---------------------------------------------------------------- refresh

    @Test
    void refresh_readsTheTokenFromTheCookie_andSetsTheRotatedOne() throws Exception {
        when(authService.refresh("old-raw")).thenReturn(new AuthTokens("new-access", "new-raw"));

        mvc.perform(post("/api/v1/auth/refresh").cookie(new Cookie(COOKIE_NAME, "old-raw")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.accessToken").value("new-access"))
                .andExpect(header().string(HttpHeaders.SET_COOKIE, containsString(COOKIE_NAME + "=new-raw")));
        verify(authService).refresh("old-raw");
    }

    @Test
    void refresh_withoutCookie_is401() throws Exception {
        when(authService.refresh(null)).thenThrow(new BusinessException(ErrorCode.INVALID_REFRESH_TOKEN));

        mvc.perform(post("/api/v1/auth/refresh"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("INVALID_REFRESH_TOKEN"));
    }

    // ---------------------------------------------------------------- logout

    @Test
    void logout_revokesTheCookieToken_returns204_andClearsTheCookie() throws Exception {
        mvc.perform(post("/api/v1/auth/logout").cookie(new Cookie(COOKIE_NAME, "raw")))
                .andExpect(status().isNoContent())
                .andExpect(header().string(HttpHeaders.SET_COOKIE, containsString(COOKIE_NAME + "=;")))
                .andExpect(header().string(HttpHeaders.SET_COOKIE, containsString("Max-Age=0")))
                .andExpect(header().string(HttpHeaders.SET_COOKIE, containsString("Path=/api/v1/auth")));
        verify(authService).logout("raw");
    }

    @Test
    void logout_withoutCookie_isStill204() throws Exception {
        mvc.perform(post("/api/v1/auth/logout"))
                .andExpect(status().isNoContent());
        verify(authService).logout(null);
    }

    // ---------------------------------------------------------------- register

    @Test
    void register_returns201_withPublicIdAndNoPassword() throws Exception {
        when(authService.register("bob@example.com", "password123", "Bob"))
                .thenReturn(new User("bob@example.com", "$2a$hash", "Bob", UserRole.CUSTOMER));

        mvc.perform(post("/api/v1/auth/register").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"bob@example.com\",\"password\":\"password123\",\"fullName\":\"Bob\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").isNotEmpty())
                .andExpect(jsonPath("$.email").value("bob@example.com"))
                .andExpect(jsonPath("$.role").value("CUSTOMER"))
                .andExpect(content().string(not(containsString("password"))))
                .andExpect(content().string(not(containsString("$2a$hash"))));
    }

    @Test
    void register_invalidInput_is400_listingTheBadFields() throws Exception {
        mvc.perform(post("/api/v1/auth/register").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"not-an-email\",\"password\":\"short\",\"fullName\":\"Bob\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors.length()").value(2));
    }

    @Test
    void register_emailTaken_is409() throws Exception {
        when(authService.register("bob@example.com", "password123", "Bob"))
                .thenThrow(new BusinessException(ErrorCode.EMAIL_ALREADY_EXISTS));

        mvc.perform(post("/api/v1/auth/register").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"bob@example.com\",\"password\":\"password123\",\"fullName\":\"Bob\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("EMAIL_ALREADY_EXISTS"));
    }
}
