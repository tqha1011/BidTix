package com.bidtix.config;

import com.bidtix.support.PostgresIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.micrometer.metrics.test.autoconfigure.AutoConfigureMetrics;
import org.springframework.boot.test.context.SpringBootTest;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Without the {@code monitoring} profile the application port is the management port. Only the health status is open
 * there: the metrics path is on the security whitelist too, so it must stay unexposed.
 */
@AutoConfigureMetrics   // the registry is there, so only the exposure setting keeps the metrics in
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "bidtix.scheduling.enabled=false")
class ActuatorDefaultExposureTest extends PostgresIntegrationTest {

    private final HttpClient http = HttpClient.newHttpClient();

    @Value("${local.server.port}") int port;

    @Test
    void noMetricsAreServed() {
        HttpResponse<String> answer = get("/actuator/prometheus");

        assertThat(answer.statusCode()).isNotEqualTo(200);
        assertThat(answer.body()).doesNotContain("jvm_memory_used_bytes");
    }

    @Test
    void healthSaysOnlyUpOrDown_withoutAToken() {
        HttpResponse<String> answer = get("/actuator/health");

        assertThat(answer.statusCode()).isEqualTo(200);
        assertThat(answer.body()).contains("\"status\":\"UP\"").doesNotContain("components", "details");
    }

    @Test
    void otherManagementRoutesAndTheApiStillNeedAToken() {
        for (String path : new String[]{"/actuator", "/actuator/metrics", "/actuator/env", "/api/v1/events"}) {
            assertThat(get(path).statusCode()).as(path).isEqualTo(401);
        }
    }

    private HttpResponse<String> get(String path) {
        try {
            return http.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path)).build(),
                    HttpResponse.BodyHandlers.ofString());
        } catch (IOException e) {
            throw new IllegalStateException(e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }
}
