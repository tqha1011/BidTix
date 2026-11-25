package com.bidtix.support;

import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import java.util.UUID;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;

/**
 * Base class for tests that call the API over MockMvc, security included. Rate limiting is off: these tests are about
 * the endpoints, and it has its own tests.
 */
@TestPropertySource(properties = "bidtix.rate-limit.enabled=false")
public abstract class ApiTest extends PostgresIntegrationTest {

    @Autowired WebApplicationContext context;
    @Autowired protected JdbcTemplate jdbc;

    protected MockMvc mvc;

    @BeforeEach
    void setUpMockMvc() {
        mvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
    }

    /** A new user with this role, inserted by SQL; returns the public id. */
    protected UUID user(String role) {
        UUID publicId = UUID.randomUUID();
        jdbc.update("insert into users (public_id, email, password_hash, full_name, role) values (?, ?, 'hash', ?, ?)",
                publicId, publicId + "@test.dev", "Test " + role, role);
        return publicId;
    }

    /** A request as this user, the way a verified access token would look. */
    protected static RequestPostProcessor as(UUID userPublicId, String role) {
        return jwt().jwt(token -> token.subject(userPublicId.toString()))
                .authorities(new SimpleGrantedAuthority("ROLE_" + role));
    }
}
