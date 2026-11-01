package io.capdevila.poc.sr4j.lab;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import io.github.resilience4j.common.circuitbreaker.configuration.CircuitBreakerConfigCustomizer;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.resttestclient.TestRestTemplate;
import org.springframework.boot.resttestclient.autoconfigure.AutoConfigureTestRestTemplate;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpStatus;
import org.springframework.test.context.ActiveProfiles;

import static org.assertj.core.api.Assertions.assertThat;

/** Real HTTP/AOP outcomes with a deterministic native phase clock, without forced phase changes. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
    "lab.autostart=false",
    "resilience4j.circuitbreaker.configs.default.automaticTransitionFromOpenToHalfOpenEnabled=false",
    "resilience4j.circuitbreaker.configs.default.maxWaitDurationInHalfOpenState=0"
})
@AutoConfigureTestRestTemplate
@ActiveProfiles("lab")
@Import(FinalPhaseLabIntegrationTests.PhaseClockConfiguration.class)
class FinalPhaseLabIntegrationTests {
    private static final Duration DWELL = Duration.ofSeconds(60);
    @Autowired LabTrafficService traffic;
    @Autowired CircuitBreakerRegistry registry;
    @Autowired TestRestTemplate http;
    @Autowired ObjectMapper mapper;
    @Autowired MutableClock clock;
    private CircuitBreaker smart;

    @BeforeEach
    void startFromAnIsolatedPausedTrial() {
        traffic.select("pause");
        clock.reset();
        configure(75, "STEP_BACK", 50, 50);
    }

    @Test
    void eightFailuresAtHundredPercentStepBackOnlyAfterFullWindowDurationAndFreshCompletion() {
        var standard = registry.circuitBreaker(LabTrafficService.STANDARD);
        enterFinalPhase();
        fillNeutralWindow();
        assertThat(smart.getMetrics().getFailureRate()).isEqualTo(8);
        assertPhase(100);
        clock.advance(DWELL.minusMillis(1));
        outcome(100);
        assertPhase(100);
        clock.advance(Duration.ofMillis(1));
        assertPhase(100); // The clock alone cannot decide the phase.
        outcome(100);
        assertPhase(75);
        assertThat(smart.getMetrics().getNumberOfBufferedCalls()).isZero();
        assertThat(registry.circuitBreaker(LabTrafficService.STANDARD)).isSameAs(standard);
        assertThat(standard.getState()).isEqualTo(CircuitBreaker.State.CLOSED);
        assertThat(standard.getMetrics().getNumberOfBufferedCalls()).isZero();
        assertThat(traffic.status().events()).anySatisfy(event -> {
            assertThat(event.from()).isEqualTo("100");
            assertThat(event.to()).isEqualTo("75");
            assertThat(event.reason()).isEqualTo("STEPPED_BACK");
        });
    }

    @Test
    void openStrategyRejectsTheSameNeutralFinalWindowAfterItsDuration() {
        configure(75, "OPEN", 50, 50);
        enterFinalPhase();
        fillNeutralWindow();
        clock.advance(DWELL);
        assertPhase(100);
        outcome(100);
        assertThat(smart.getState()).isEqualTo(CircuitBreaker.State.OPEN);
    }

    @Test
    void singleHundredPercentPhaseHasNoPredecessorAndReopensAfterNeutralValidation() {
        configure(100, "STEP_BACK", 50, 50);
        enterRecovery();
        fillNeutralWindow();
        assertPhase(100);
        clock.advance(DWELL);
        outcome(100);
        assertThat(smart.getState()).isEqualTo(CircuitBreaker.State.OPEN);
    }

    @Test
    void healthyFinalWindowClosesAfterTheSameSampleAndDurationGates() {
        enterFinalPhase();
        for (int index = 0; index < 100; index++) outcome(100);
        assertPhase(100);
        clock.advance(DWELL);
        assertPhase(100);
        outcome(100);
        assertThat(smart.getState()).isEqualTo(CircuitBreaker.State.CLOSED);
    }

    @Test
    void finalValidationCannotRejectAnIncompleteWindowEvenAfterItsDuration() {
        enterFinalPhase();
        clock.advance(DWELL);
        for (int index = 0; index < 91; index++) outcome(100);
        for (int index = 0; index < 8; index++) outcome(0);
        assertThat(smart.getMetrics().getNumberOfBufferedCalls()).isEqualTo(99);
        assertPhase(100);
        outcome(100);
        assertPhase(75);
    }

    @Test
    void severeFinalWindowStillRegressesBeforeTheMinimumDuration() {
        enterFinalPhase();
        for (int index = 0; index < 80; index++) outcome(100);
        for (int index = 0; index < 19; index++) outcome(0);
        assertPhase(100);
        outcome(0);
        assertPhase(75);
    }

    @Test
    void ordinaryFailureThresholdCanPreventClosingWithoutChangingSmartAdvanceBounds() {
        configure(75, "STEP_BACK", 1, 50);
        enterFinalPhase();
        for (int index = 0; index < 97; index++) outcome(100);
        for (int index = 0; index < 3; index++) outcome(0);
        assertThat(smart.getMetrics().getFailureRate()).isEqualTo(3);
        assertPhase(100); // Within smart 5%, but ordinary 1% would consider this window unhealthy.
        clock.advance(DWELL);
        outcome(100);
        assertPhase(75);
        assertThat(smart.getCircuitBreakerConfig().getFailureRateThreshold()).isEqualTo(1);
        assertThat(smart.getCircuitBreakerConfig().getHalfOpenRecoveryConfig().getMaxFailureRateForAdvance()).isEqualTo(5);
    }

    @Test
    void ordinarySlowThresholdAlsoGuardsFinalClosureUsingTheSameWindow() {
        configure(75, "STEP_BACK", 50, 5);
        enterFinalPhase();
        for (int index = 0; index < 92; index++) outcome(100);
        for (int index = 0; index < 8; index++) outcome(100, "?latencyMillis=250");
        assertThat(smart.getMetrics().getFailureRate()).isZero();
        assertThat(smart.getMetrics().getSlowCallRate()).isBetween(8f, 10f);
        assertPhase(100); // Smart permits <=10% slow; ordinary CLOSED requires <5%.
        clock.advance(DWELL);
        outcome(100);
        assertPhase(75);
    }

    @Test
    void intermediateNeutralWindowStillHoldsAfterTheDuration() {
        enterRecovery();
        fillNeutralWindow();
        clock.advance(DWELL);
        outcome(100);
        assertPhase(75);
        assertThat(smart.getMetrics().getFailureRate()).isEqualTo(8);
    }

    private void configure(int initial, String strategy, int ordinaryFailures, int ordinarySlow) {
        var request = mapper.createObjectNode().put("trafficPercentageStep", 25)
            .put("minimumPhaseDurationMillis", DWELL.toMillis()).put("minimumNumberOfCalls", 100)
            .put("initialTrafficPercentage", initial).put("failureStrategy", strategy)
            .put("recoveryMaxFailureRateForAdvance", 5).put("recoveryFailureRateThreshold", 20)
            .put("recoveryMaxSlowCallRateForAdvance", 10).put("recoverySlowCallRateThreshold", 50)
            .put("failureRateThreshold", ordinaryFailures).put("slowCallRateThreshold", ordinarySlow)
            .put("slowCallDurationThresholdMillis", ordinarySlow == 5 ? 200 : 2_000)
            .put("slidingWindowSize", 100).put("closedMinimumNumberOfCalls", 100)
            .put("standardHalfOpenPermittedCalls", 10).put("maxWaitDurationInHalfOpenStateMillis", 0)
            .put("automaticTransitionFromOpenToHalfOpenEnabled", false).put("requestsPerSecond", 0)
            .put("restartTrial", true);
        assertThat(http.postForEntity("/lab/api/configuration", request, JsonNode.class).getStatusCode()).isEqualTo(HttpStatus.OK);
        smart = registry.circuitBreaker(LabTrafficService.SMART);
        assertThat(smart.getCircuitBreakerConfig().getClock()).isSameAs(clock);
    }

    private void enterRecovery() {
        smart.transitionToOpenState();
        smart.transitionToHalfOpenState();
    }

    private void enterFinalPhase() {
        enterRecovery();
        for (int index = 0; index < 100; index++) outcome(100);
        assertPhase(75);
        clock.advance(DWELL);
        outcome(100);
        assertPhase(100);
        assertThat(smart.getMetrics().getNumberOfBufferedCalls()).isZero();
    }

    private void fillNeutralWindow() {
        for (int index = 0; index < 92; index++) outcome(100);
        for (int index = 0; index < 8; index++) outcome(0);
    }

    private void outcome(int successRatio) { outcome(successRatio, ""); }

    private void outcome(int successRatio, String query) {
        long before = smart.getAdmissionMetrics().getNumberOfPermittedAttempts();
        for (int attempt = 0; attempt < 100; attempt++) {
            http.getForEntity("/process/" + successRatio + query, String.class);
            if (smart.getAdmissionMetrics().getNumberOfPermittedAttempts() > before) return;
        }
        throw new AssertionError("No real HTTP call admitted within 100 attempts");
    }

    private void assertPhase(int percentage) {
        assertThat(smart.getState()).isEqualTo(CircuitBreaker.State.HALF_OPEN);
        assertThat(smart.getHalfOpenPhase().getPermittedTrafficPercentage()).isEqualTo(percentage);
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class PhaseClockConfiguration {
        @Bean MutableClock phaseClock() { return new MutableClock(); }
        @Bean CircuitBreakerConfigCustomizer phaseClockCustomizer(MutableClock phaseClock) {
            return CircuitBreakerConfigCustomizer.of("default", builder -> builder.clock(phaseClock));
        }
    }

    static final class MutableClock extends Clock {
        private final AtomicReference<Instant> now = new AtomicReference<>();
        MutableClock() { reset(); }
        void reset() { now.set(Instant.parse("2026-01-01T00:00:00Z")); }
        void advance(Duration duration) { now.updateAndGet(instant -> instant.plus(duration)); }
        @Override public ZoneId getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(ZoneId zone) {
            if (!zone.equals(ZoneOffset.UTC)) throw new IllegalArgumentException("Only UTC is supported by this test clock");
            return this;
        }
        @Override public Instant instant() { return now.get(); }
    }
}
