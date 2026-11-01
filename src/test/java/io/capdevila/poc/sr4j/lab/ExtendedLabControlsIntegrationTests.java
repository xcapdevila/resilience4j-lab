package io.capdevila.poc.sr4j.lab;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Duration;
import java.util.Set;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.actuate.observability.AutoConfigureObservability;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.ActiveProfiles;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
    "lab.autostart=false",
    "lab.test.extended-controls=true",
    "resilience4j.circuitbreaker.configs.default.waitDurationInOpenState=30s"
})
@ActiveProfiles("lab")
@AutoConfigureObservability
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ExtendedLabControlsIntegrationTests {
    @Autowired LabTrafficService traffic;
    @Autowired CircuitBreakerRegistry registry;
    @Autowired TestRestTemplate http;
    @Autowired ObjectMapper mapper;
    @Autowired MeterRegistry meters;
    private ObjectNode defaults;

    @BeforeAll
    void captureTheApplicationsConfiguredDefaults() {
        defaults = mapper.createObjectNode();
        status().path("configuration").fields().forEachRemaining(entry -> {
            if (!Set.of("revision", "trialRevision", "instanceId", "trialStartedAtEpochMillis").contains(entry.getKey()))
                defaults.set(entry.getKey(), entry.getValue());
        });
    }

    @BeforeEach
    void restoreKnownSettingsAndStopBackgroundOffers() {
        pauseAndDrain();
        assertThat(post(defaults.deepCopy().put("restartTrial", true)).getStatusCode()).isEqualTo(HttpStatus.OK);
        registry.circuitBreaker(LabTrafficService.SMART).reset();
        registry.circuitBreaker(LabTrafficService.STANDARD).reset();
    }

    @AfterEach
    void stopBackgroundTraffic() { pauseAndDrain(); }

    @Test
    void hotTrafficAndServiceChangesPreserveRecoveryStateSamplesAndCounterOwnership() {
        assertThat(post(required().put("trafficPercentageStep", 50)
            .put("minimumPhaseDurationMillis", 3_600_000).put("minimumNumberOfCalls", 20)
            .put("restartTrial", true)).getStatusCode()).isEqualTo(HttpStatus.OK);
        var smart = registry.circuitBreaker(LabTrafficService.SMART);
        var standard = registry.circuitBreaker(LabTrafficService.STANDARD);
        smart.transitionToOpenState();
        smart.transitionToHalfOpenState();
        for (int offer = 0; offer < 4; offer++) http.getForEntity("/process/100", String.class);
        assertThat(smart.getMetrics().getNumberOfBufferedCalls()).isEqualTo(2);
        long attempts = smart.getAdmissionMetrics().getNumberOfAttempts();
        JsonNode before = status().path("configuration");

        var response = post(required().put("requestsPerSecond", 3).put("maxConcurrentRequests", 7)
            .put("backendSuccessRatio", 95).put("backendLatencyMillis", 50)
            .put("slowCallPercentage", 20).put("slowExtraLatencyMillis", 200)
            .put("healthyDurationMillis", 1_000).put("restartTrial", false));
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        JsonNode after = response.getBody().path("configuration");
        assertThat(after.path("requestsPerSecond").asInt()).isEqualTo(3);
        assertThat(after.path("revision").asLong()).isGreaterThan(before.path("revision").asLong());
        assertThat(after.path("trialRevision")).isEqualTo(before.path("trialRevision"));
        assertThat(after.path("trialStartedAtEpochMillis").asLong()).isPositive()
            .isEqualTo(before.path("trialStartedAtEpochMillis").asLong());
        assertThat(after.path("instanceId").asText()).isNotBlank().isEqualTo(before.path("instanceId").asText());
        assertThat(registry.circuitBreaker(LabTrafficService.SMART)).isSameAs(smart);
        assertThat(registry.circuitBreaker(LabTrafficService.STANDARD)).isSameAs(standard);
        assertThat(smart.getState()).isEqualTo(CircuitBreaker.State.HALF_OPEN);
        assertThat(smart.getHalfOpenPhase().getPermittedTrafficPercentage()).isEqualTo(50);
        assertThat(smart.getMetrics().getNumberOfBufferedCalls()).isEqualTo(2);
        assertThat(smart.getAdmissionMetrics().getNumberOfAttempts()).isEqualTo(attempts);
        assertThat(response.getBody().path("requestedRatePerCircuit").asInt()).isEqualTo(3);
        assertThat(response.getBody().path("scenario").asText()).isEqualTo("pause");
    }

    @Test
    void aFreshTrialAdvancesTheEvidenceBoundaryButPreservesProcessIdentity() {
        JsonNode before = status().path("configuration");
        // Wait for a later clock tick without making the boundary depend on an
        // arbitrary sleep or on how quickly the HTTP configuration call runs.
        await().atMost(Duration.ofSeconds(1)).until(() ->
            System.currentTimeMillis() > before.path("trialStartedAtEpochMillis").asLong());

        var response = post(required().put("restartTrial", true));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        JsonNode after = response.getBody().path("configuration");
        assertThat(after.path("trialRevision").asLong()).isEqualTo(before.path("trialRevision").asLong() + 1);
        assertThat(after.path("trialStartedAtEpochMillis").asLong())
            .isGreaterThan(before.path("trialStartedAtEpochMillis").asLong())
            .isLessThanOrEqualTo(System.currentTimeMillis());
        assertThat(after.path("instanceId")).isEqualTo(before.path("instanceId"));
    }

    @Test
    void changingRecoveryDuringAHotUpdateIsRejectedAtomically() {
        var smart = registry.circuitBreaker(LabTrafficService.SMART);
        JsonNode before = status().path("configuration");
        int currentStep = before.path("trafficPercentageStep").asInt();
        var response = post(required().put("trafficPercentageStep", currentStep == 50 ? 25 : 50)
            .put("requestsPerSecond", 2).put("restartTrial", false));
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(status().path("configuration")).isEqualTo(before);
        assertThat(registry.circuitBreaker(LabTrafficService.SMART)).isSameAs(smart);
    }

    @Test
    void sharedBreakerSettingsRebuildBothPoliciesWithoutMakingTheStandardCircuitGradual() {
        var oldSmart = registry.circuitBreaker(LabTrafficService.SMART);
        var oldStandard = registry.circuitBreaker(LabTrafficService.STANDARD);
        JsonNode before = status().path("configuration");
        var response = post(required().put("failureRateThreshold", 25.5)
            .put("slowCallRateThreshold", 20).put("slowCallDurationThresholdMillis", 50)
            .put("waitDurationInOpenStateMillis", 250).put("slidingWindowSize", 12)
            .put("closedMinimumNumberOfCalls", 4).put("standardHalfOpenPermittedCalls", 7)
            .put("automaticTransitionFromOpenToHalfOpenEnabled", false).put("restartTrial", true));
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        var smart = registry.circuitBreaker(LabTrafficService.SMART);
        var standard = registry.circuitBreaker(LabTrafficService.STANDARD);
        assertThat(smart).isNotSameAs(oldSmart);
        assertThat(standard).isNotSameAs(oldStandard);
        assertThat(oldSmart.getState()).isEqualTo(CircuitBreaker.State.FORCED_OPEN);
        assertThat(oldStandard.getState()).isEqualTo(CircuitBreaker.State.FORCED_OPEN);
        for (var breaker : new CircuitBreaker[] {smart, standard}) {
            var config = breaker.getCircuitBreakerConfig();
            assertThat(config.getFailureRateThreshold()).isEqualTo(25.5f);
            assertThat(config.getSlowCallRateThreshold()).isEqualTo(20);
            assertThat(config.getSlowCallDurationThreshold()).isEqualTo(Duration.ofMillis(50));
            assertThat(config.getWaitIntervalFunctionInOpenState().apply(1)).isEqualTo(250);
            assertThat(config.getSlidingWindowSize()).isEqualTo(12);
            assertThat(config.getMinimumNumberOfCalls()).isEqualTo(4);
            assertThat(config.isAutomaticTransitionFromOpenToHalfOpenEnabled()).isFalse();
        }
        assertThat(smart.getCircuitBreakerConfig().getHalfOpenRecoveryConfig()).isNotNull();
        assertThat(standard.getCircuitBreakerConfig().getHalfOpenRecoveryConfig()).isNull();
        assertThat(standard.getCircuitBreakerConfig().getPermittedNumberOfCallsInHalfOpenState()).isEqualTo(7);
        assertThat(response.getBody().path("configuration").path("trialRevision").asLong())
            .isGreaterThan(before.path("trialRevision").asLong());
        assertThat(meters.find("resilience4j.circuitbreaker.recovery.phase")
            .tag("name", LabTrafficService.STANDARD).gauge()).isNull();
        assertThat(traffic.status().events()).noneSatisfy(event -> assertThat(event.to()).isEqualTo("FORCED_OPEN"));
    }

    @Test
    void smartHealthBandControlsReplaceOnlyRecoveryAndRejectInvalidOrHotChangesAtomically() {
        var oldSmart = registry.circuitBreaker(LabTrafficService.SMART);
        var standard = registry.circuitBreaker(LabTrafficService.STANDARD);
        var ordinary = standard.getCircuitBreakerConfig();
        var response = post(required().put("recoveryMaxFailureRateForAdvance", 0)
            .put("recoveryFailureRateThreshold", 12.5).put("recoveryMaxSlowCallRateForAdvance", 5)
            .put("recoverySlowCallRateThreshold", 25).put("restartTrial", true));
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        var smart = registry.circuitBreaker(LabTrafficService.SMART);
        var recovery = smart.getCircuitBreakerConfig().getHalfOpenRecoveryConfig();
        assertThat(smart).isNotSameAs(oldSmart);
        assertThat(registry.circuitBreaker(LabTrafficService.STANDARD)).isSameAs(standard);
        assertThat(recovery.getMaxFailureRateForAdvance()).isZero();
        assertThat(recovery.getFailureRateThreshold()).isEqualTo(12.5f);
        assertThat(recovery.getMaxSlowCallRateForAdvance()).isEqualTo(5);
        assertThat(recovery.getSlowCallRateThreshold()).isEqualTo(25);
        assertThat(smart.getCircuitBreakerConfig().getFailureRateThreshold()).isEqualTo(ordinary.getFailureRateThreshold());
        assertThat(smart.getCircuitBreakerConfig().getSlowCallRateThreshold()).isEqualTo(ordinary.getSlowCallRateThreshold());
        assertThat(standard.getCircuitBreakerConfig().getHalfOpenRecoveryConfig()).isNull();
        JsonNode before = status().path("configuration");
        for (var invalid : new ObjectNode[] {
            required().put("recoveryMaxFailureRateForAdvance", 12.5),
            required().put("recoveryMaxSlowCallRateForAdvance", 25),
            required().put("recoveryFailureRateThreshold", 0),
            required().put("recoveryMaxFailureRateForAdvance", 5.0000001).put("recoveryFailureRateThreshold", 5.0000002),
            required().put("recoveryMaxFailureRateForAdvance", 1).put("restartTrial", false)}) {
            assertThat(post(invalid).getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
            assertThat(status().path("configuration")).isEqualTo(before);
            assertThat(registry.circuitBreaker(LabTrafficService.SMART)).isSameAs(smart);
            assertThat(registry.circuitBreaker(LabTrafficService.STANDARD)).isSameAs(standard);
        }
    }

    @Test
    void actualHttpOutcomesHoldInTheBandAndNeedAFullWindowBeforeSteppingBack() {
        assertThat(post(required().put("trafficPercentageStep", 50).put("minimumPhaseDurationMillis", 100)
            .put("minimumNumberOfCalls", 20).put("failureStrategy", "STEP_BACK")
            .put("recoveryMaxFailureRateForAdvance", 5).put("recoveryFailureRateThreshold", 20)
            .put("recoveryMaxSlowCallRateForAdvance", 10).put("recoverySlowCallRateThreshold", 50)
            .put("restartTrial", true)).getStatusCode()).isEqualTo(HttpStatus.OK);
        var smart = registry.circuitBreaker(LabTrafficService.SMART);
        smart.transitionToOpenState();
        smart.transitionToHalfOpenState();
        await().pollDelay(Duration.ofMillis(150)).atMost(Duration.ofSeconds(2)).untilAsserted(() ->
            assertThat(smart.getHalfOpenPhase().getPermittedTrafficPercentage()).isEqualTo(50));

        // Two failures out of twenty is 10%: too unhealthy to advance, but
        // below the 20% regression threshold. Traffic continues at 50%.
        admittedOutcome(smart, 0);
        assertThat(smart.getState()).isEqualTo(CircuitBreaker.State.HALF_OPEN);
        assertThat(smart.getMetrics().getNumberOfBufferedCalls()).isEqualTo(1);
        admittedOutcome(smart, 0);
        for (int index = 0; index < 18; index++) admittedOutcome(smart, 100);
        assertThat(smart.getMetrics().getFailureRate()).isEqualTo(10);
        assertThat(smart.getHalfOpenPhase().getPermittedTrafficPercentage()).isEqualTo(50);
        JsonNode held = status().path("circuits").get(0);
        assertThat(held.path("failureRate").asDouble()).isEqualTo(10);
        assertThat(held.path("slowCallRate").asDouble()).isZero();

        // One old failure leaves the rolling window: 1/20 = 5%, exactly
        // the inclusive advance maximum, now validates the next phase.
        admittedOutcome(smart, 100);
        assertThat(smart.getHalfOpenPhase().getPermittedTrafficPercentage()).isEqualTo(100);
        assertThat(smart.getMetrics().getNumberOfBufferedCalls()).isZero();
        admittedOutcome(smart, 0);
        assertThat(smart.getHalfOpenPhase().getPermittedTrafficPercentage()).isEqualTo(100);
        assertThat(smart.getMetrics().getNumberOfBufferedCalls()).isEqualTo(1);
        for (int index = 0; index < 3; index++) admittedOutcome(smart, 0);
        for (int index = 0; index < 16; index++) admittedOutcome(smart, 100);
        assertThat(smart.getState()).isEqualTo(CircuitBreaker.State.HALF_OPEN);
        assertThat(smart.getHalfOpenPhase().getPermittedTrafficPercentage()).isEqualTo(50);
        assertThat(traffic.status().events()).anySatisfy(event -> {
            assertThat(event.from()).isEqualTo("100");
            assertThat(event.to()).isEqualTo("50");
            assertThat(event.reason()).isEqualTo("STEPPED_BACK");
        });
    }

    private void admittedOutcome(CircuitBreaker smart, int successRatio) {
        long before = smart.getAdmissionMetrics().getNumberOfPermittedAttempts();
        for (int attempt = 0; attempt < 100; attempt++) {
            http.getForEntity("/process/" + successRatio, String.class);
            if (smart.getAdmissionMetrics().getNumberOfPermittedAttempts() > before) return;
        }
        throw new AssertionError("No HTTP call was admitted within 100 attempts");
    }

    @Test
    void configuredConcurrencyBoundsRealRequestsAndReportsTheActualRateSeparately() {
        var response = post(required().put("requestsPerSecond", 1_000)
            .put("maxConcurrentRequests", 1).put("backendLatencyMillis", 100)
            .put("slowCallPercentage", 0).put("restartTrial", false));
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        traffic.select("healthy");
        try {
            await().atMost(Duration.ofSeconds(8)).untilAsserted(() -> {
                JsonNode snapshot = status();
                assertThat(snapshot.path("requestedRatePerCircuit").asInt()).isEqualTo(1_000);
                for (JsonNode circuit : snapshot.path("circuits")) {
                    assertThat(circuit.path("completedRequests").asLong()).isGreaterThanOrEqualTo(5);
                    assertThat(circuit.path("skippedRequests").asLong()).isPositive();
                    assertThat(circuit.path("inFlightRequests").asInt()).isBetween(0, 1);
                    assertThat(circuit.path("observedRequestsPerSecond").isNumber()).isTrue();
                    assertThat(circuit.path("observedRequestsPerSecond").asDouble()).isGreaterThan(0).isLessThan(100);
                }
            });
        } finally { pauseAndDrain(); }
        assertThat(traffic.status().transportErrors()).isZero();
    }

    @Test
    void actualSuccessfulAndFailedLatenciesAreRecordedInsideTheNativeCircuitBreaker() {
        assertThat(post(required().put("slowCallDurationThresholdMillis", 20)
            .put("slowCallRateThreshold", 100).put("failureRateThreshold", 100)
            .put("slidingWindowSize", 10).put("closedMinimumNumberOfCalls", 5)
            .put("restartTrial", true)).getStatusCode()).isEqualTo(HttpStatus.OK);
        for (String route : new String[] {"/process/", "/process-standard/"}) {
            var success = http.getForEntity(route + "100?latencyMillis=70", String.class);
            var failure = http.getForEntity(route + "0?slowCallPercentage=100&slowExtraLatencyMillis=70", String.class);
            assertThat(success.getStatusCode()).isEqualTo(HttpStatus.OK);
            assertThat(failure.getStatusCode()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
            var breaker = registry.circuitBreaker(route.equals("/process/") ? LabTrafficService.SMART : LabTrafficService.STANDARD);
            assertThat(breaker.getMetrics().getNumberOfBufferedCalls()).isEqualTo(2);
            assertThat(breaker.getMetrics().getNumberOfSuccessfulCalls()).isEqualTo(1);
            assertThat(breaker.getMetrics().getNumberOfFailedCalls()).isEqualTo(1);
            assertThat(breaker.getMetrics().getNumberOfSlowCalls()).isEqualTo(2);
            var timer = meters.get("resilience4j.circuitbreaker.calls").tag("name", breaker.getName())
                .tag("kind", "successful").timer();
            assertThat(timer.totalTime(java.util.concurrent.TimeUnit.MILLISECONDS)).isGreaterThanOrEqualTo(60);
        }
    }

    private ObjectNode required() {
        JsonNode current = status().path("configuration");
        return mapper.createObjectNode().put("trafficPercentageStep", current.path("trafficPercentageStep").asInt())
            .put("minimumPhaseDurationMillis", current.path("minimumPhaseDurationMillis").asLong());
    }

    private ResponseEntity<JsonNode> post(ObjectNode request) {
        return http.postForEntity("/lab/api/configuration", request, JsonNode.class);
    }

    private JsonNode status() { return http.getForObject("/lab/api/status", JsonNode.class); }

    private void pauseAndDrain() {
        traffic.select("pause");
        await().atMost(Duration.ofSeconds(8)).untilAsserted(() -> {
            var snapshot = traffic.status();
            assertThat(snapshot.completedRequests() + snapshot.transportErrors()).isEqualTo(snapshot.dispatchedRequests());
        });
    }
}
