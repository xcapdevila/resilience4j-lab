package io.capdevila.poc.sr4j.lab;

import tools.jackson.databind.JsonNode;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.micrometer.metrics.test.autoconfigure.AutoConfigureMetrics;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.resttestclient.TestRestTemplate;
import org.springframework.boot.resttestclient.autoconfigure.AutoConfigureTestRestTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.ActiveProfiles;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
    "lab.autostart=false",
    "resilience4j.circuitbreaker.configs.default.waitDurationInOpenState=30s"
})
@AutoConfigureTestRestTemplate
@ActiveProfiles("lab")
@AutoConfigureMetrics
class LabConfigurationIntegrationTests {
    private static final String ATTEMPTS = "resilience4j.circuitbreaker.admission.attempts";
    private static final String TARGET = "resilience4j.circuitbreaker.recovery.traffic.percentage";

    @Autowired LabTrafficService traffic;
    @Autowired CircuitBreakerRegistry registry;
    @Autowired TestRestTemplate http;
    @Autowired MeterRegistry meters;

    @BeforeEach
    void pauseTrafficAndStartFromClosedCircuits() {
        pauseAndDrain();
        // Every test starts with the same recovery settings, independently of
        // preceding tests that deliberately exercise large sample windows.
        assertThat(configure(25, 2_000, "STEP_BACK", 10).getStatusCode()).isEqualTo(HttpStatus.OK);
        registry.circuitBreaker(LabTrafficService.SMART).reset();
        registry.circuitBreaker(LabTrafficService.STANDARD).reset();
    }

    @AfterEach
    void leaveNoBackgroundTrafficOrOpenTimers() {
        pauseAndDrain();
        registry.circuitBreaker(LabTrafficService.SMART).reset();
        registry.circuitBreaker(LabTrafficService.STANDARD).reset();
    }

    @Test
    void replacesOnlySmartPolicyAndRebindsActualAopAndPrometheusMetrics() {
        var oldSmart = registry.circuitBreaker(LabTrafficService.SMART);
        var standard = registry.circuitBreaker(LabTrafficService.STANDARD);
        var original = oldSmart.getCircuitBreakerConfig();
        long before = oldSmart.getAdmissionMetrics().getNumberOfAttempts();
        for (int index = 0; index < 7; index++) {
            assertThat(http.getForEntity("/process/100", String.class).getStatusCode()).isEqualTo(HttpStatus.OK);
        }
        long oldAttempts = oldSmart.getAdmissionMetrics().getNumberOfAttempts();
        assertThat(oldAttempts - before).isEqualTo(7);
        standard.transitionToOpenState();
        long revision = status().path("configuration").path("revision").asLong();

        var response = configure(50, 1_234);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().path("configuration").path("trafficPercentageStep").asInt()).isEqualTo(50);
        assertThat(response.getBody().path("configuration").path("minimumPhaseDurationMillis").asLong()).isEqualTo(1_234);
        assertThat(response.getBody().path("configuration").path("revision").asLong()).isEqualTo(revision + 1);
        assertThat(response.getBody().path("scenario").asString()).isEqualTo("pause");

        var replacement = registry.circuitBreaker(LabTrafficService.SMART);
        assertThat(replacement).isNotSameAs(oldSmart);
        assertThat(oldSmart.getState()).isEqualTo(CircuitBreaker.State.FORCED_OPEN);
        assertThat(traffic.status().events()).noneSatisfy(event ->
            assertThat(event.to()).isEqualTo("FORCED_OPEN"));
        assertThat(replacement.getState()).isEqualTo(CircuitBreaker.State.CLOSED);
        assertThat(registry.circuitBreaker(LabTrafficService.STANDARD)).isSameAs(standard);
        assertThat(standard.getState()).isEqualTo(CircuitBreaker.State.OPEN);
        var config = replacement.getCircuitBreakerConfig();
        var policy = config.getHalfOpenRecoveryConfig();
        assertThat(policy.getTrafficPercentageStep()).isEqualTo(50);
        assertThat(policy.getInitialTrafficPercentage()).isEqualTo(50);
        assertThat(policy.getMinimumPhaseDuration()).isEqualTo(Duration.ofMillis(1_234));
        assertThat(policy.getMinimumNumberOfCalls()).isEqualTo(original.getHalfOpenRecoveryConfig().getMinimumNumberOfCalls());
        assertThat(policy.getFailureStrategy()).isEqualTo(original.getHalfOpenRecoveryConfig().getFailureStrategy());
        assertThat(config.getFailureRateThreshold()).isEqualTo(original.getFailureRateThreshold());
        assertThat(config.getSlidingWindowSize()).isEqualTo(original.getSlidingWindowSize());
        assertThat(config.getMinimumNumberOfCalls()).isEqualTo(original.getMinimumNumberOfCalls());
        assertThat(replacement.getTags()).isEqualTo(oldSmart.getTags());
        assertThat(replacement.getAdmissionMetrics().getNumberOfAttempts()).isZero();
        assertThat(meters.get(ATTEMPTS).tag("name", LabTrafficService.SMART).functionCounter().count()).isZero();

        // Fast-forward only the OPEN wait. Actual HTTP requests still traverse the
        // controller, aspect and percentage admission policy of the replacement.
        replacement.transitionToOpenState();
        replacement.transitionToHalfOpenState();
        assertThat(meters.get(TARGET).tag("name", LabTrafficService.SMART).gauge().value()).isEqualTo(50);
        assertThat(prometheusSample("resilience4j_circuitbreaker_recovery_traffic_percentage")).isEqualTo(50);
        for (int index = 0; index < 16; index++) http.getForEntity("/process/100", String.class);

        assertThat(replacement.getAdmissionMetrics().getNumberOfAttempts()).isEqualTo(16);
        assertThat(replacement.getAdmissionMetrics().getNumberOfPermittedAttempts()).isPositive();
        assertThat(oldSmart.getAdmissionMetrics().getNumberOfAttempts()).isEqualTo(oldAttempts);
        assertThat(meters.get(ATTEMPTS).tag("name", LabTrafficService.SMART).functionCounter().count()).isEqualTo(16);
        assertThat(prometheusSample("resilience4j_circuitbreaker_admission_attempts_total")).isEqualTo(16);
        assertThat(meters.find(ATTEMPTS).tag("name", LabTrafficService.STANDARD).functionCounter()).isNull();
    }

    @Test
    void invalidPayloadsPreserveCircuitSettingsRevisionAndSelectedScenario() {
        var smart = registry.circuitBreaker(LabTrafficService.SMART);
        JsonNode previous = status();
        List<String> invalid = List.of(
            "{}",
            "{\"trafficPercentageStep\":25}",
            "{\"minimumPhaseDurationMillis\":2000}",
            "{\"trafficPercentageStep\":0,\"minimumPhaseDurationMillis\":2000}",
            "{\"trafficPercentageStep\":120,\"minimumPhaseDurationMillis\":2000}",
            "{\"trafficPercentageStep\":25,\"minimumPhaseDurationMillis\":0}",
            "{\"trafficPercentageStep\":25,\"minimumPhaseDurationMillis\":99}",
            "{\"trafficPercentageStep\":25,\"minimumPhaseDurationMillis\":3600001}",
            "{\"trafficPercentageStep\":null,\"minimumPhaseDurationMillis\":2000}",
            "{\"trafficPercentageStep\":25,\"minimumPhaseDurationMillis\":null}",
            "{\"trafficPercentageStep\":25.5,\"minimumPhaseDurationMillis\":2000}",
            "{\"trafficPercentageStep\":25,\"minimumPhaseDurationMillis\":1000.5}",
            "{\"trafficPercentageStep\":\"invalid\",\"minimumPhaseDurationMillis\":2000}",
            "{\"trafficPercentageStep\":25,\"minimumPhaseDurationMillis\":\"invalid\"}",
            "{\"trafficPercentageStep\":25,\"minimumPhaseDurationMillis\":2000,\"failureStrategy\":null}",
            "{\"trafficPercentageStep\":25,\"minimumPhaseDurationMillis\":2000,\"failureStrategy\":42}",
            "{\"trafficPercentageStep\":25,\"minimumPhaseDurationMillis\":2000,\"failureStrategy\":true}",
            "{\"trafficPercentageStep\":25,\"minimumPhaseDurationMillis\":2000,\"failureStrategy\":[]}",
            "{\"trafficPercentageStep\":25,\"minimumPhaseDurationMillis\":2000,\"failureStrategy\":\"open\"}",
            "{\"trafficPercentageStep\":25,\"minimumPhaseDurationMillis\":2000,\"failureStrategy\":\"RETRY\"}",
            "{\"trafficPercentageStep\":25,\"minimumPhaseDurationMillis\":2000,\"failureStrategy\":\"\"}",
            "{\"trafficPercentageStep\":25,\"minimumPhaseDurationMillis\":2000,\"minimumNumberOfCalls\":null}",
            "{\"trafficPercentageStep\":25,\"minimumPhaseDurationMillis\":2000,\"minimumNumberOfCalls\":0}",
            "{\"trafficPercentageStep\":25,\"minimumPhaseDurationMillis\":2000,\"minimumNumberOfCalls\":-1}",
            "{\"trafficPercentageStep\":25,\"minimumPhaseDurationMillis\":2000,\"minimumNumberOfCalls\":100001}",
            "{\"trafficPercentageStep\":25,\"minimumPhaseDurationMillis\":2000,\"minimumNumberOfCalls\":2147483648}",
            "{\"trafficPercentageStep\":25,\"minimumPhaseDurationMillis\":2000,\"minimumNumberOfCalls\":2.5}",
            "{\"trafficPercentageStep\":25,\"minimumPhaseDurationMillis\":2000,\"minimumNumberOfCalls\":\"20\"}",
            "{\"trafficPercentageStep\":25,\"minimumPhaseDurationMillis\":2000,\"minimumNumberOfCalls\":true}",
            "{\"trafficPercentageStep\":25,\"minimumPhaseDurationMillis\":2000,\"minimumNumberOfCalls\":[]}"
        );
        for (String payload : invalid) {
            var headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);
            var response = http.postForEntity("/lab/api/configuration", new HttpEntity<>(payload, headers), JsonNode.class);
            assertThat(response.getStatusCode()).as(payload).isEqualTo(HttpStatus.BAD_REQUEST);
            JsonNode after = status();
            assertThat(after.path("configuration")).as(payload).isEqualTo(previous.path("configuration"));
            assertThat(after.path("scenario")).isEqualTo(previous.path("scenario"));
            assertThat(registry.circuitBreaker(LabTrafficService.SMART)).isSameAs(smart);
        }
        assertThat(http.getForEntity("/lab/api/configuration", String.class).getStatusCode())
            .isEqualTo(HttpStatus.METHOD_NOT_ALLOWED);
    }

    @Test
    void acceptsMinimumAndMaximumControlsWithoutSilentlyClampingThem() {
        for (var values : List.of(new long[] {1, 100}, new long[] {100, 3_600_000})) {
            var response = configure((int) values[0], values[1]);
            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
            var policy = registry.circuitBreaker(LabTrafficService.SMART).getCircuitBreakerConfig().getHalfOpenRecoveryConfig();
            assertThat(policy.getTrafficPercentageStep()).isEqualTo((int) values[0]);
            assertThat(policy.getMinimumPhaseDuration()).isEqualTo(Duration.ofMillis(values[1]));
        }
    }

    @Test
    void appliesNewConfigurationWhileRealTrafficRunsAndResumesItsScenario() {
        traffic.select("outage");
        var oldSmart = registry.circuitBreaker(LabTrafficService.SMART);
        var standard = registry.circuitBreaker(LabTrafficService.STANDARD);
        await().atMost(Duration.ofSeconds(8)).untilAsserted(() ->
            assertThat(oldSmart.getState()).isEqualTo(CircuitBreaker.State.OPEN));

        var response = configure(30, 200);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody().path("scenario").asString()).isEqualTo("outage");
        assertThat(response.getBody().path("running").asBoolean()).isTrue();
        var replacement = registry.circuitBreaker(LabTrafficService.SMART);
        assertThat(replacement).isNotSameAs(oldSmart);
        assertThat(registry.circuitBreaker(LabTrafficService.STANDARD)).isSameAs(standard);
        long retiredAttempts = oldSmart.getAdmissionMetrics().getNumberOfAttempts();
        await().atMost(Duration.ofSeconds(8)).untilAsserted(() -> {
            assertThat(replacement.getAdmissionMetrics().getNumberOfAttempts()).isPositive();
            assertThat(replacement.getState()).isEqualTo(CircuitBreaker.State.OPEN);
        });
        pauseAndDrain();
        assertThat(oldSmart.getAdmissionMetrics().getNumberOfAttempts()).isEqualTo(retiredAttempts);
        assertThat(meters.get(ATTEMPTS).tag("name", LabTrafficService.SMART).functionCounter().count())
            .isEqualTo(replacement.getAdmissionMetrics().getNumberOfAttempts());
        assertThat(traffic.status().transportErrors()).isZero();
    }

    @Test
    void selectedOpenStrategyReopensOnAnActualUnhealthyRecoveryPhase() {
        assertActualUnhealthyPhase("OPEN");
    }

    @Test
    void selectedStepBackStrategyRetreatsOnTheSameActualUnhealthyRecoveryPhase() {
        assertActualUnhealthyPhase("STEP_BACK");
    }

    @Test
    void configurationPostsWithoutStrategyPreserveThePreviouslySelectedStrategy() {
        for (String strategy : List.of("OPEN", "STEP_BACK")) {
            assertThat(configure(25, 200, strategy).getStatusCode()).isEqualTo(HttpStatus.OK);
            var response = configure(50, 300);
            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
            assertThat(response.getBody().path("configuration").path("failureStrategy").asString())
                .isEqualTo(strategy);
            assertThat(registry.circuitBreaker(LabTrafficService.SMART).getCircuitBreakerConfig()
                .getHalfOpenRecoveryConfig().getFailureStrategy().name()).isEqualTo(strategy);
        }
    }

    @Test
    void sampleThresholdGatesRealCompletedCallsAfterTheSameMinimumDwell() {
        var standard = registry.circuitBreaker(LabTrafficService.STANDARD);
        int closedMinimum = registry.circuitBreaker(LabTrafficService.SMART).getCircuitBreakerConfig().getMinimumNumberOfCalls();
        for (int samples : List.of(2, 20)) {
            var response = configure(50, 100, "STEP_BACK", samples);
            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
            assertThat(response.getBody().path("configuration").path("minimumNumberOfCalls").asInt()).isEqualTo(samples);
            var smart = registry.circuitBreaker(LabTrafficService.SMART);
            assertThat(smart.getCircuitBreakerConfig().getHalfOpenRecoveryConfig().getMinimumNumberOfCalls()).isEqualTo(samples);
            assertThat(smart.getCircuitBreakerConfig().getMinimumNumberOfCalls()).isEqualTo(closedMinimum);
            assertThat(registry.circuitBreaker(LabTrafficService.STANDARD)).isSameAs(standard);

            // Skip only the OPEN timer, then let the real minimum phase duration
            // elapse without offers. Time alone must not advance either policy.
            smart.transitionToOpenState();
            smart.transitionToHalfOpenState();
            await().pollDelay(Duration.ofMillis(150)).atMost(Duration.ofSeconds(2)).untilAsserted(() -> {
                assertThat(smart.getHalfOpenPhase().getPermittedTrafficPercentage()).isEqualTo(50);
                assertThat(smart.getAdmissionMetrics().getNumberOfAttempts()).isZero();
            });

            if (samples == 2) {
                for (int offer = 0; offer < 4; offer++) http.getForEntity("/process/100", String.class);
                assertThat(smart.getHalfOpenPhase().getPermittedTrafficPercentage()).isEqualTo(100);
            } else {
                for (int offer = 0; offer < 20; offer++) http.getForEntity("/process/100", String.class);
                assertThat(smart.getAdmissionMetrics().getNumberOfAttempts()).isEqualTo(20);
                assertThat(smart.getAdmissionMetrics().getNumberOfPermittedAttempts()).isEqualTo(10);
                assertThat(smart.getHalfOpenPhase().getPermittedTrafficPercentage()).isEqualTo(50);
                assertThat(prometheusSample("resilience4j_circuitbreaker_recovery_traffic_percentage")).isEqualTo(50);

                // Twenty offered calls include ten rejections: only another ten
                // admitted completions fill the twenty-sample recovery window.
                for (int offer = 0; offer < 20; offer++) http.getForEntity("/process/100", String.class);
                assertThat(smart.getHalfOpenPhase().getPermittedTrafficPercentage()).isEqualTo(100);
                assertThat(prometheusSample("resilience4j_circuitbreaker_recovery_traffic_percentage")).isEqualTo(100);
            }
        }
    }

    @Test
    void configurationPostsWithoutSamplesPreserveANonDefaultRecoveryWindow() {
        assertThat(configure(25, 200, "OPEN", 37).getStatusCode()).isEqualTo(HttpStatus.OK);
        var response = configure(50, 300);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody().path("configuration").path("minimumNumberOfCalls").asInt()).isEqualTo(37);
        assertThat(response.getBody().path("configuration").path("failureStrategy").asString()).isEqualTo("OPEN");
        assertThat(registry.circuitBreaker(LabTrafficService.SMART).getCircuitBreakerConfig()
            .getHalfOpenRecoveryConfig().getMinimumNumberOfCalls()).isEqualTo(37);
    }

    @Test
    void acceptsSampleWindowBoundariesWithoutSilentlyClamping() {
        for (int samples : List.of(1, 100_000)) {
            var response = configure(25, 200, "STEP_BACK", samples);
            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
            assertThat(response.getBody().path("configuration").path("minimumNumberOfCalls").asInt()).isEqualTo(samples);
            assertThat(registry.circuitBreaker(LabTrafficService.SMART).getCircuitBreakerConfig()
                .getHalfOpenRecoveryConfig().getMinimumNumberOfCalls()).isEqualTo(samples);
        }
    }

    private void assertActualUnhealthyPhase(String strategy) {
        var response = configure(50, 100, strategy);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody().path("configuration").path("failureStrategy").asString())
            .isEqualTo(strategy);
        var smart = registry.circuitBreaker(LabTrafficService.SMART);
        assertThat(smart.getCircuitBreakerConfig().getHalfOpenRecoveryConfig().getFailureStrategy().name())
            .isEqualTo(strategy);

        // Skip only the OPEN wait. Both policies receive the same real HTTP
        // successes to reach the second phase, then the same backend failures.
        smart.transitionToOpenState();
        smart.transitionToHalfOpenState();
        for (int index = 0; index < 20; index++) http.getForEntity("/process/100", String.class);
        await().pollInterval(Duration.ofMillis(10)).atMost(Duration.ofSeconds(3)).untilAsserted(() -> {
            http.getForEntity("/process/100", String.class);
            assertThat(smart.getHalfOpenPhase().getPermittedTrafficPercentage()).isEqualTo(100);
        });
        int beforeFailures = traffic.status().events().size();
        for (int index = 0; index < 20 && smart.getState() == CircuitBreaker.State.HALF_OPEN
            && smart.getHalfOpenPhase().getPermittedTrafficPercentage() == 100; index++) {
            assertThat(http.getForEntity("/process/0", String.class).getStatusCode())
                .isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
        }
        var events = traffic.status().events();
        var failureEvents = events.subList(beforeFailures, events.size());
        if (strategy.equals("OPEN")) {
            assertThat(smart.getState()).isEqualTo(CircuitBreaker.State.OPEN);
            assertThat(prometheusSample("resilience4j_circuitbreaker_recovery_traffic_percentage")).isZero();
            assertThat(failureEvents).anySatisfy(event -> {
                assertThat(event.name()).isEqualTo(LabTrafficService.SMART);
                assertThat(event.type()).isEqualTo("STATE_TRANSITION");
                assertThat(event.from()).isEqualTo("HALF_OPEN");
                assertThat(event.to()).isEqualTo("OPEN");
            });
            assertThat(failureEvents).noneSatisfy(event -> assertThat(event.reason()).isEqualTo("STEPPED_BACK"));
        } else {
            assertThat(smart.getState()).isEqualTo(CircuitBreaker.State.HALF_OPEN);
            assertThat(smart.getHalfOpenPhase().getPermittedTrafficPercentage()).isEqualTo(50);
            assertThat(prometheusSample("resilience4j_circuitbreaker_recovery_traffic_percentage")).isEqualTo(50);
            assertThat(failureEvents).anySatisfy(event -> {
                assertThat(event.name()).isEqualTo(LabTrafficService.SMART);
                assertThat(event.type()).isEqualTo("HALF_OPEN_PHASE_TRANSITION");
                assertThat(event.from()).isEqualTo("100");
                assertThat(event.to()).isEqualTo("50");
                assertThat(event.reason()).isEqualTo("STEPPED_BACK");
            });
        }
    }

    private ResponseEntity<JsonNode> configure(int step, long minimumPhaseDurationMillis) {
        return http.postForEntity("/lab/api/configuration", Map.of(
            "trafficPercentageStep", step, "minimumPhaseDurationMillis", minimumPhaseDurationMillis), JsonNode.class);
    }

    private ResponseEntity<JsonNode> configure(int step, long minimumPhaseDurationMillis, String strategy) {
        return http.postForEntity("/lab/api/configuration", Map.of(
            "trafficPercentageStep", step, "minimumPhaseDurationMillis", minimumPhaseDurationMillis,
            "failureStrategy", strategy), JsonNode.class);
    }

    private ResponseEntity<JsonNode> configure(int step, long minimumPhaseDurationMillis, String strategy, int samples) {
        return http.postForEntity("/lab/api/configuration", Map.of(
            "trafficPercentageStep", step, "minimumPhaseDurationMillis", minimumPhaseDurationMillis,
            "failureStrategy", strategy, "minimumNumberOfCalls", samples), JsonNode.class);
    }

    private JsonNode status() { return http.getForObject("/lab/api/status", JsonNode.class); }

    private void pauseAndDrain() {
        traffic.select("pause");
        await().atMost(Duration.ofSeconds(5)).untilAsserted(() -> {
            var current = traffic.status();
            assertThat(current.completedRequests() + current.transportErrors()).isEqualTo(current.dispatchedRequests());
        });
    }

    private double prometheusSample(String metric) {
        return http.getForObject("/actuator/prometheus", String.class).lines()
            .filter(line -> line.startsWith(metric + "{") && line.contains("name=\"" + LabTrafficService.SMART + "\""))
            .mapToDouble(line -> Double.parseDouble(line.substring(line.indexOf('}') + 1).trim().split("\\s+")[0]))
            .findFirst().orElseThrow();
    }
}
