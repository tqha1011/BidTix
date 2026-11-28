package com.bidtix.config.exceptions;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import com.bidtix.config.metrics.Metrics;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.MediaType;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authentication.LockedException;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** No Spring context: a throwaway controller throws each exception and we check the response. */
class GlobalExceptionHandlerTest {

    private static final UUID SEAT_1 = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID SEAT_2 = UUID.fromString("22222222-2222-2222-2222-222222222222");

    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        mvc = MockMvcBuilders.standaloneSetup(new ThrowingController())
                .setControllerAdvice(new GlobalExceptionHandler(new Metrics(new SimpleMeterRegistry())))
                .build();
    }

    @Test
    void businessException_usesStatusAndCodeFromErrorCode() throws Exception {
        mvc.perform(get("/business"))
                .andExpect(status().isConflict())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.status").value(409))
                .andExpect(jsonPath("$.code").value("SEAT_UNAVAILABLE"))
                .andExpect(jsonPath("$.detail").value("Seat A12 is held"));
    }

    @Test
    void businessException_withProperties_addsThemNextToCode() throws Exception {
        mvc.perform(get("/business-with-properties"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("SEAT_UNAVAILABLE"))
                .andExpect(jsonPath("$.detail").value("Some seats are unavailable"))
                .andExpect(jsonPath("$.seatPublicIds.length()").value(2))
                .andExpect(jsonPath("$.seatPublicIds[0]").value(SEAT_1.toString()))
                .andExpect(jsonPath("$.seatPublicIds[1]").value(SEAT_2.toString()));
    }

    @Test
    void businessException_withoutProperties_addsNoExtraFields() throws Exception {
        mvc.perform(get("/business"))
                .andExpect(jsonPath("$.seatPublicIds").doesNotExist());
    }

    @Test
    void wrongPassword_and_lockedAccount_lookIdentical() throws Exception {
        for (String path : new String[]{"/bad-password", "/locked"}) {
            mvc.perform(get(path))
                    .andExpect(status().isUnauthorized())
                    .andExpect(jsonPath("$.code").value("INVALID_CREDENTIALS"))
                    .andExpect(jsonPath("$.detail").value("Invalid email or password"));
        }
    }

    @Test
    void accessDenied_is403() throws Exception {
        mvc.perform(get("/denied"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("FORBIDDEN"));
    }

    @Test
    void invalidBody_is400WithFieldErrors() throws Exception {
        mvc.perform(post("/validate").contentType(MediaType.APPLICATION_JSON).content("{\"email\":\"\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST"))
                .andExpect(jsonPath("$.errors[0].field").value("email"));
    }

    @Test
    void malformedJson_isHandledByFrameworkAs400() throws Exception {
        mvc.perform(post("/validate").contentType(MediaType.APPLICATION_JSON).content("{not json"))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON));
    }

    @Test
    void databaseConstraint_is409_andDoesNotLeakSqlDetails() throws Exception {
        mvc.perform(get("/constraint"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("CONFLICT"))
                .andExpect(content().string(not(containsString("ux_tickets_active_seat"))));
    }

    @Test
    void unexpectedException_is500_andDoesNotLeakMessage() throws Exception {
        mvc.perform(get("/boom"))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.code").value("INTERNAL_ERROR"))
                .andExpect(content().string(not(containsString("secret table name"))));
    }

    @RestController
    static class ThrowingController {

        record Body(@NotBlank String email) {
        }

        @GetMapping("/business")
        void business() {
            throw new BusinessException(ErrorCode.SEAT_UNAVAILABLE, "Seat A12 is held");
        }

        @GetMapping("/business-with-properties")
        void businessWithProperties() {
            throw new BusinessException(ErrorCode.SEAT_UNAVAILABLE, "Some seats are unavailable",
                    Map.of("seatPublicIds", List.of(SEAT_1, SEAT_2)));
        }

        @GetMapping("/bad-password")
        void badPassword() {
            throw new BadCredentialsException("Bad credentials");
        }

        @GetMapping("/locked")
        void locked() {
            throw new LockedException("User account is locked");
        }

        @GetMapping("/denied")
        void denied() {
            throw new AccessDeniedException("Access Denied");
        }

        @PostMapping("/validate")
        void validate(@Valid @RequestBody Body body) {
        }

        @GetMapping("/constraint")
        void constraint() {
            throw new DataIntegrityViolationException("duplicate key violates ux_tickets_active_seat");
        }

        @GetMapping("/boom")
        void boom() {
            throw new IllegalStateException("secret table name");
        }
    }
}
