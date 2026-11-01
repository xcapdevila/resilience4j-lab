package io.capdevila.poc.sr4j.lab;

import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.actuate.observability.AutoConfigureObservability;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.test.context.ActiveProfiles;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
    "lab.autostart=false",
    "resilience4j.circuitbreaker.configs.default.waitDurationInOpenState=30s"
})
@ActiveProfiles("lab")
@AutoConfigureObservability
class LiveLabIntegrationTests {
    @Autowired LabTrafficService traffic;
    @Autowired CircuitBreakerRegistry registry;
    @Autowired TestRestTemplate http;

    @Test
    void realHttpTrafficOpensBothNativeCircuitsAndExportsTheirActualMetrics() {
        assertThat(traffic.status().running()).isFalse();
        assertThat(http.getForEntity("/lab/", String.class).getBody()).contains("Errors during recovery");
        try {
            assertThat(http.postForEntity("/lab/api/scenario/outage", null, String.class)
                .getStatusCode().is2xxSuccessful()).isTrue();
            await().atMost(Duration.ofSeconds(8)).untilAsserted(() -> {
                assertThat(registry.circuitBreaker(LabTrafficService.SMART).getState()).isEqualTo(CircuitBreaker.State.OPEN);
                assertThat(registry.circuitBreaker(LabTrafficService.STANDARD).getState()).isEqualTo(CircuitBreaker.State.OPEN);
                assertThat(traffic.status().completedRequests()).isGreaterThanOrEqualTo(10);
            });
            var status = traffic.status();
            assertThat(status.transportErrors()).isZero();
            assertThat(status.events()).anySatisfy(event -> {
                assertThat(event.name()).isEqualTo(LabTrafficService.SMART);
                assertThat(event.from()).isEqualTo("CLOSED");
                assertThat(event.to()).isEqualTo("OPEN");
            });
            String scrape = http.getForObject("/actuator/prometheus", String.class);
            assertThat(scrape).contains("resilience4j_circuitbreaker_admission_attempts_total");
            assertThat(scrape).contains("resilience4j_circuitbreaker_recovery_traffic_percentage");
            assertThat(scrape).contains("name=\"DemoService.processStandard\"");
        } finally {
            traffic.select("pause");
        }
        assertThat(traffic.status().running()).isFalse();
    }
}
