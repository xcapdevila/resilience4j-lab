package io.capdevila.poc.sr4j;

import tools.jackson.databind.ObjectMapper;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import io.github.resilience4j.circuitbreaker.HalfOpenRecoveryConfig;
import io.github.resilience4j.circuitbreaker.event.CircuitBreakerEvent;
import io.github.resilience4j.circuitbreaker.event.CircuitBreakerOnHalfOpenPhaseTransitionEvent;
import io.github.resilience4j.common.circuitbreaker.configuration.CircuitBreakerConfigCustomizer;
import io.github.resilience4j.common.circuitbreaker.monitoring.endpoint.CircuitBreakerEventsEndpointResponse;
import io.github.resilience4j.micrometer.tagged.CircuitBreakerMetricNames;
import io.github.resilience4j.spring6.circuitbreaker.configure.CircuitBreakerConfigurationProperties;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.test.web.servlet.MockMvc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(properties = {
    "resilience4j.circuitbreaker.configs.default.automaticTransitionFromOpenToHalfOpenEnabled=false",
    "resilience4j.circuitbreaker.configs.default.maxWaitDurationInHalfOpenState=0"
})
@AutoConfigureMockMvc
@Import(HalfOpenRecoveryIntegrationTests.RecoveryClockConfiguration.class)
class HalfOpenRecoveryIntegrationTests {

    private static final String CIRCUIT_BREAKER_NAME = "DemoService.process";
    private static final Instant TEST_INSTANT = Instant.parse("2026-01-01T00:00:00Z");

    @Autowired
    private MockMvc mvc;

    @Autowired
    private CircuitBreakerRegistry registry;

    @Autowired
    private CircuitBreakerConfigurationProperties configurationProperties;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private MutableClock clock;

    @Autowired(required = false)
    private MeterRegistry meterRegistry;

    private CircuitBreaker circuitBreaker;

    @BeforeEach
    void resetCircuitBreakerAndClock() {
        clock.reset();
        circuitBreaker = registry.circuitBreaker(CIRCUIT_BREAKER_NAME);
        circuitBreaker.reset();
        assertThat(circuitBreaker.getCircuitBreakerConfig().getClock()).isSameAs(clock);
    }

    @Test
    void bindsAllRecoveryOptionsFromTheApplicationProperties() {
        var properties = configurationProperties.getInstances().get(CIRCUIT_BREAKER_NAME).getHalfOpenRecovery();
        var recovery = circuitBreaker.getCircuitBreakerConfig().getHalfOpenRecoveryConfig();

        assertThat(properties.getEnabled()).isTrue();
        assertThat(recovery).isNotNull();
        assertThat(recovery.getInitialTrafficPercentage()).isEqualTo(5);
        assertThat(recovery.isInitialTrafficPercentageAutomatic()).isTrue();
        assertThat(recovery.getTrafficPercentageStep()).isEqualTo(5);
        assertThat(recovery.getMinimumPhaseDuration()).isEqualTo(Duration.ofSeconds(1));
        assertThat(recovery.getMinimumNumberOfCalls()).isEqualTo(10);
        assertThat(recovery.getFailureStrategy()).isEqualTo(HalfOpenRecoveryConfig.FailureStrategy.STEP_BACK);
        assertThat(recovery.getMaxFailureRateForAdvance()).isEqualTo(5);
        assertThat(recovery.getFailureRateThreshold()).isEqualTo(20);
        assertThat(recovery.getMaxSlowCallRateForAdvance()).isEqualTo(10);
        assertThat(recovery.getSlowCallRateThreshold()).isEqualTo(50);
    }

    @Test
    void admitsFivePercentAndUsesFallbackForTheRemainingAttempts() throws Exception {
        enterRecovery();

        int admitted = makeHealthyBackendAttempts(100);

        assertThat(admitted).isEqualTo(5);
        assertThat(circuitBreaker.getMetrics().getNumberOfSuccessfulCalls()).isEqualTo(5);
        assertThat(circuitBreaker.getMetrics().getNumberOfFailedCalls()).isZero();
        assertThat(circuitBreaker.getMetrics().getNumberOfNotPermittedCalls()).isEqualTo(95);
        assertThat(circuitBreaker.getState()).isEqualTo(CircuitBreaker.State.HALF_OPEN);
        assertThat(circuitBreaker.getHalfOpenPhase().getPermittedTrafficPercentage()).isEqualTo(5);
    }

    @Test
    void advancesFromFiveToTenPercentOnlyAfterSamplesAndDuration() throws Exception {
        enterRecovery();

        assertThat(makeHealthyBackendAttempts(200)).isEqualTo(10);
        assertThat(circuitBreaker.getHalfOpenPhase().getPermittedTrafficPercentage()).isEqualTo(5);

        clock.advance(Duration.ofSeconds(1));
        // Only a fresh recorded completion can validate the duration and health gates.
        assertThat(circuitBreaker.getHalfOpenPhase().getPermittedTrafficPercentage()).isEqualTo(5);
        int additionalSuccesses = makeHealthyBackendAttempts(1);

        assertThat(circuitBreaker.getState()).isEqualTo(CircuitBreaker.State.HALF_OPEN);
        assertThat(circuitBreaker.getHalfOpenPhase()).isEqualTo(new CircuitBreaker.HalfOpenPhase(2, 20, 10));
        assertThat(additionalSuccesses).isEqualTo(1);
        assertThat(circuitBreaker.getMetrics().getNumberOfSuccessfulCalls()).isZero();
    }

    @Test
    void healthReportsThePublicHalfOpenState() throws Exception {
        enterRecovery();

        mvc.perform(get("/actuator/health"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.components.circuitBreakers.details['DemoService.process'].status")
                .value("CIRCUIT_HALF_OPEN"))
            .andExpect(jsonPath("$.components.circuitBreakers.details['DemoService.process'].details.state")
                .value("HALF_OPEN"));
    }

    @Test
    void autoConfiguredGaugesExposeThePhaseAndTargetPercentage() throws Exception {
        assumeTrue(meterRegistry != null, "MeterRegistry auto-configuration is unavailable");
        var names = CircuitBreakerMetricNames.ofDefaults();
        var phase = meterRegistry.find(names.getRecoveryPhaseMetricName()).tag("name", CIRCUIT_BREAKER_NAME).gauge();
        var percentage = meterRegistry.find(names.getRecoveryTrafficPercentageMetricName()).tag("name", CIRCUIT_BREAKER_NAME).gauge();
        var successes = meterRegistry.find(names.getCallsMetricName())
            .tags("name", CIRCUIT_BREAKER_NAME, "kind", "successful").timer();
        var attempts = meterRegistry.get(names.getAttemptsMetricName())
            .tag("name", CIRCUIT_BREAKER_NAME).functionCounter();
        var permitted = meterRegistry.get(names.getPermittedAttemptsMetricName())
            .tag("name", CIRCUIT_BREAKER_NAME).functionCounter();
        assertThat(phase).isNotNull();
        assertThat(percentage).isNotNull();
        assertThat(successes).isNotNull();
        long successfulCallsBeforeRecovery = successes.count();
        double attemptsBeforeRecovery = attempts.count();
        double permittedBeforeRecovery = permitted.count();

        enterRecovery();
        assertThat(phase.value()).isEqualTo(1);
        assertThat(percentage.value()).isEqualTo(5);

        assertThat(makeHealthyBackendAttempts(200)).isEqualTo(10);
        clock.advance(Duration.ofSeconds(1));
        int additionalSuccesses = makeHealthyBackendAttempts(1);

        assertThat(phase.value()).isEqualTo(2);
        assertThat(percentage.value()).isEqualTo(10);
        assertThat(successes.count()).isEqualTo(successfulCallsBeforeRecovery + 10 + additionalSuccesses);
        assertThat(attempts.count()).isEqualTo(attemptsBeforeRecovery + 201);
        assertThat(permitted.count()).isEqualTo(permittedBeforeRecovery + 10 + additionalSuccesses);
    }

    @Test
    void admissionCountersMeasureObservedTrafficAlongsideTheTargetAndSurviveReset() throws Exception {
        assertThat(meterRegistry).isNotNull();
        var names = CircuitBreakerMetricNames.ofDefaults();
        var attempts = meterRegistry.get(names.getAttemptsMetricName())
            .tag("name", CIRCUIT_BREAKER_NAME).functionCounter();
        var permitted = meterRegistry.get(names.getPermittedAttemptsMetricName())
            .tag("name", CIRCUIT_BREAKER_NAME).functionCounter();
        var target = meterRegistry.get(names.getRecoveryTrafficPercentageMetricName())
            .tag("name", CIRCUIT_BREAKER_NAME).gauge();
        double attemptsBefore = attempts.count();
        double permittedBefore = permitted.count();
        enterRecovery();

        assertThat(makeHealthyBackendAttempts(100)).isEqualTo(5);

        double observedAttempts = attempts.count() - attemptsBefore;
        double observedPermitted = permitted.count() - permittedBefore;
        assertThat(observedAttempts).isEqualTo(100);
        assertThat(observedPermitted).isEqualTo(5);
        assertThat(100 * observedPermitted / observedAttempts).isEqualTo(5);
        assertThat(target.value()).isEqualTo(5);

        circuitBreaker.reset();

        assertThat(attempts.count()).isEqualTo(attemptsBefore + 100);
        assertThat(permitted.count()).isEqualTo(permittedBefore + 5);
        assertThat(target.value()).isZero();
        assertThat(makeHealthyBackendAttempts(1)).isEqualTo(1);
        assertThat(attempts.count()).isEqualTo(attemptsBefore + 101);
        assertThat(permitted.count()).isEqualTo(permittedBefore + 6);
        assertThat(circuitBreaker.getAdmissionMetrics().getNumberOfAttempts()).isEqualTo((long) attempts.count());
        assertThat(circuitBreaker.getAdmissionMetrics().getNumberOfPermittedAttempts()).isEqualTo((long) permitted.count());
    }

    @Test
    void countsAdmissionBeforeCompletionAndDoesNotCountCompletionAgain() {
        assertThat(meterRegistry).isNotNull();
        var names = CircuitBreakerMetricNames.ofDefaults();
        var attempts = meterRegistry.get(names.getAttemptsMetricName())
            .tag("name", CIRCUIT_BREAKER_NAME).functionCounter();
        var permitted = meterRegistry.get(names.getPermittedAttemptsMetricName())
            .tag("name", CIRCUIT_BREAKER_NAME).functionCounter();
        var successes = meterRegistry.get(names.getCallsMetricName())
            .tags("name", CIRCUIT_BREAKER_NAME, "kind", "successful").timer();
        double attemptsBefore = attempts.count();
        double permittedBefore = permitted.count();
        long completedBefore = successes.count();
        enterRecovery();

        List<CompletableFuture<String>> pending = new ArrayList<>();
        for (int i = 0; i < 100; i++) {
            circuitBreaker.executeCompletionStage(() -> {
                CompletableFuture<String> call = new CompletableFuture<>();
                pending.add(call);
                return call;
            });
        }

        assertThat(pending).hasSize(5);
        assertThat(attempts.count()).isEqualTo(attemptsBefore + 100);
        assertThat(permitted.count()).isEqualTo(permittedBefore + 5);
        assertThat(successes.count()).isEqualTo(completedBefore);
        pending.forEach(call -> call.complete("OK"));
        assertThat(attempts.count()).isEqualTo(attemptsBefore + 100);
        assertThat(permitted.count()).isEqualTo(permittedBefore + 5);
        assertThat(successes.count()).isEqualTo(completedBefore + 5);
    }

    @Test
    void actuatorPhaseEventRoundTripsThroughTheActualSpringObjectMapper() throws Exception {
        enterRecovery();

        String json = mvc.perform(get("/actuator/circuitbreakerevents/{name}/{eventType}",
                CIRCUIT_BREAKER_NAME, "HALF_OPEN_PHASE_TRANSITION"))
            .andExpect(status().isOk())
            .andReturn().getResponse().getContentAsString();
        var response = objectMapper.readValue(json, CircuitBreakerEventsEndpointResponse.class);

        assertThat(response.getCircuitBreakerEvents()).isNotEmpty();
        var events = response.getCircuitBreakerEvents();
        var event = events.get(events.size() - 1);
        assertThat(event.getType()).isEqualTo(CircuitBreakerEvent.Type.HALF_OPEN_PHASE_TRANSITION);
        assertThat(event.getCircuitBreakerName()).isEqualTo(CIRCUIT_BREAKER_NAME);
        assertThat(event.getHalfOpenPhaseTransitionReason())
            .isEqualTo(CircuitBreakerOnHalfOpenPhaseTransitionEvent.Reason.ENTERED);
        assertThat(event.getPreviousHalfOpenPhase()).isEqualTo(CircuitBreaker.HalfOpenPhase.inactive());
        assertThat(event.getHalfOpenPhase()).isEqualTo(new CircuitBreaker.HalfOpenPhase(1, 20, 5));
    }

    private void enterRecovery() {
        circuitBreaker.transitionToOpenState();
        circuitBreaker.transitionToHalfOpenState();
    }

    private int makeHealthyBackendAttempts(int attempts) throws Exception {
        int admitted = 0;
        for (int i = 0; i < attempts; i++) {
            var response = mvc.perform(get("/process/100")).andReturn().getResponse();
            if (response.getStatus() == 200) {
                assertThat(response.getContentAsString()).isEqualTo("OK");
                admitted++;
            } else {
                assertThat(response.getStatus()).isEqualTo(500);
                assertThat(response.getContentAsString())
                    .startsWith("Circuit breaker fallback response, caused by:")
                    .contains("does not permit further calls");
            }
        }
        return admitted;
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class RecoveryClockConfiguration {

        @Bean
        MutableClock recoveryClock() {
            return new MutableClock(new AtomicReference<>(TEST_INSTANT), ZoneOffset.UTC);
        }

        @Bean
        CircuitBreakerConfigCustomizer mutableRecoveryClock(MutableClock recoveryClock) {
            return CircuitBreakerConfigCustomizer.of("default", builder -> builder.clock(recoveryClock));
        }
    }

    static final class MutableClock extends Clock {

        private final AtomicReference<Instant> current;
        private final ZoneId zone;

        private MutableClock(AtomicReference<Instant> current, ZoneId zone) {
            this.current = current;
            this.zone = zone;
        }

        void reset() {
            current.set(TEST_INSTANT);
        }

        void advance(Duration duration) {
            current.updateAndGet(instant -> instant.plus(duration));
        }

        @Override
        public ZoneId getZone() {
            return zone;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return new MutableClock(current, zone);
        }

        @Override
        public Instant instant() {
            return current.get();
        }
    }
}
