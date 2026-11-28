package com.bidtix.config.metrics;

import com.bidtix.support.ApiTest;
import io.micrometer.core.instrument.MeterRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.UUID;

import static com.bidtix.support.Counters.value;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Every error the API answers with is counted by its code, wherever it was raised. */
class ApiErrorMetricsTest extends ApiTest {

    @Autowired MeterRegistry registry;

    @Test
    void anErrorOfAService_isCountedByItsCode() throws Exception {
        double before = errors("EVENT_NOTFOUND");

        mvc.perform(get("/api/v1/events/{id}", UUID.randomUUID()).with(as(user("CUSTOMER"), "CUSTOMER")))
                .andExpect(status().isNotFound());

        assertThat(errors("EVENT_NOTFOUND")).isEqualTo(before + 1);
    }

    @Test
    void anErrorRaisedByTheSecurityFilters_isCountedToo() throws Exception {
        double before = errors("UNAUTHORIZED");

        mvc.perform(get("/api/v1/events")).andExpect(status().isUnauthorized());

        assertThat(errors("UNAUTHORIZED")).isEqualTo(before + 1);
    }

    private double errors(String code) {
        return value(registry, "bidtix.api.errors", "code", code);
    }
}
