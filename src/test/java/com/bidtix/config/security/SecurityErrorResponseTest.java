package com.bidtix.config.security;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import com.bidtix.config.metrics.Metrics;
import com.bidtix.config.exceptions.GlobalExceptionHandler;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.security.oauth2.jwt.BadJwtException;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.junit.jupiter.web.SpringJUnitWebConfig;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.config.annotation.EnableWebMvc;

import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 401 / 403 are produced inside the security FILTER chain, before any controller runs.
 * This test boots the real {@link SecurityConfiguration} (with a fake JwtDecoder, so no RSA
 * keys or database are needed) and checks those errors use the same JSON format as the rest.
 */
@SpringJUnitWebConfig(SecurityErrorResponseTest.TestConfig.class)
class SecurityErrorResponseTest {

    @Autowired
    WebApplicationContext context;
    @Autowired
    JwtDecoder jwtDecoder;

    MockMvc mvc;

    @BeforeEach
    void setUp() {
        mvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
    }

    @Test
    void noToken_is401_inOurFormat_withBearerChallenge() throws Exception {
        mvc.perform(get("/api/v1/private"))
                .andExpect(status().isUnauthorized())
                .andExpect(header().string(HttpHeaders.WWW_AUTHENTICATE, "Bearer"))
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.status").value(401))
                .andExpect(jsonPath("$.code").value("UNAUTHORIZED"));
    }

    @Test
    void invalidToken_is401_inOurFormat() throws Exception {
        when(jwtDecoder.decode(anyString())).thenThrow(new BadJwtException("expired"));

        mvc.perform(get("/api/v1/private").header(HttpHeaders.AUTHORIZATION, "Bearer not-a-real-token"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHORIZED"));
    }

    @Test
    void validToken_reachesController() throws Exception {
        mvc.perform(get("/api/v1/private").with(jwt()))
                .andExpect(status().isOk());
    }

    /** Uses the sample "/messages/** needs scope message:read" rule in SecurityConfiguration. */
    @Test
    void validToken_withoutRequiredScope_is403_inOurFormat() throws Exception {
        mvc.perform(get("/messages/1").with(jwt()))
                .andExpect(status().isForbidden())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.code").value("FORBIDDEN"));
    }

    @Configuration
    @EnableWebMvc
    @Import({SecurityConfiguration.class, RestAuthenticationEntryPoint.class,
            RestAccessDeniedHandler.class, GlobalExceptionHandler.class, Probe.class})
    static class TestConfig {
        @Bean
        Metrics metrics() {
            return new Metrics(new SimpleMeterRegistry());
        }

        @Bean
        JwtDecoder jwtDecoder() {
            return mock(JwtDecoder.class);
        }
    }

    @RestController
    static class Probe {
        @GetMapping("/api/v1/private")
        String privateEndpoint() {
            return "ok";
        }

        @GetMapping("/messages/{id}")
        String message() {
            return "ok";
        }
    }
}
