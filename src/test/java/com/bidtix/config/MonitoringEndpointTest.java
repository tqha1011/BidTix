package com.bidtix.config;

import com.bidtix.support.PostgresIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.micrometer.metrics.test.autoconfigure.AutoConfigureMetrics;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.env.Environment;
import org.springframework.test.context.ActiveProfiles;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/**
 * The {@code monitoring} profile: Prometheus reads the metrics without a token on the management port, and nothing else
 * opens up. Real servers on random ports, the whole security chain included.
 */
@ActiveProfiles("monitoring")
@AutoConfigureMetrics   // tests switch metric export off unless asked for
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        // no scheduled jobs: one more application context must not work on the database the other tests share
        properties = {"management.server.port=0", "bidtix.scheduling.enabled=false"})
class MonitoringEndpointTest extends PostgresIntegrationTest {

    private final HttpClient http = HttpClient.newHttpClient();

    @Value("${local.server.port}") int applicationPort;
    @Value("${local.management.port}") int managementPort;
    @Autowired Environment environment;

    @Test
    void theManagementPortServesTheMetrics_withoutAToken() {
        get(applicationPort, "/api/v1/events");   // something for the HTTP metrics to have measured

        await().atMost(Duration.ofSeconds(5)).untilAsserted(() -> {
            HttpResponse<String> scrape = get(managementPort, "/actuator/prometheus");
            assertThat(scrape.statusCode()).isEqualTo(200);
            assertThat(scrape.body()).contains("jvm_memory_used_bytes", "hikaricp_connections_pending",
                    "http_server_requests_seconds_bucket",
                    "bidtix_job_item_failures_total", "bidtix_payment_vnpay_ipn_total", "bidtix_api_errors_total",
                    "bidtix_payments_refund_pending", "bidtix_payments_refund_oldest_age_seconds",
                    "bidtix_orders_expired_uncancelled", "bidtix_auctions_overdue", "bidtix_events_cancelled_uncleaned");
        });
    }

    /**
     * Tomcat's thread figures are not looked for in the scrape: the first server started without this setting switches
     * Tomcat's MBean registry off for the whole JVM, so in the test JVM they depend on which test ran first.
     */
    @Test
    void tomcatsThreadMetricsAreSwitchedOn() {
        assertThat(environment.getProperty("server.tomcat.mbeanregistry.enabled", Boolean.class)).isTrue();
    }

    @Test
    void healthIsOpenOnTheManagementPort() {
        assertThat(get(managementPort, "/actuator/health").statusCode()).isEqualTo(200);
    }

    @Test
    void theApplicationPortServesNoMetrics() {
        HttpResponse<String> answer = get(applicationPort, "/actuator/prometheus");

        assertThat(answer.statusCode()).isNotEqualTo(200);
        assertThat(answer.body()).doesNotContain("jvm_memory_used_bytes");
    }

    @Test
    void otherManagementRoutesStillNeedAToken() {
        for (String path : new String[]{"/actuator", "/actuator/metrics", "/actuator/env"}) {
            assertThat(get(managementPort, path).statusCode()).as(path).isEqualTo(401);
        }
    }

    @Test
    void theApiStillNeedsAToken() {
        assertThat(get(applicationPort, "/api/v1/events").statusCode()).isEqualTo(401);
    }

    private HttpResponse<String> get(int port, String path) {
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
