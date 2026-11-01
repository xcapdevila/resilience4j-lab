package io.capdevila.poc.sr4j;

import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import io.github.resilience4j.micrometer.tagged.CircuitBreakerMetricNames;
import io.github.resilience4j.spring6.circuitbreaker.configure.CircuitBreakerConfigurationProperties;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.micrometer.metrics.test.autoconfigure.AutoConfigureMetrics;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.web.servlet.MockMvc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(properties = {
    "resilience4j.circuitbreaker.configs.default.automaticTransitionFromOpenToHalfOpenEnabled=false",
    "resilience4j.circuitbreaker.configs.default.maxWaitDurationInHalfOpenState=0"
})
@AutoConfigureMockMvc
@AutoConfigureMetrics
@Import(Sr4jApplicationTests.DeterministicClockConfiguration.class)
class DualModeCircuitBreakerIntegrationTests {

    private static final String STANDARD = "DemoService.processStandard";
    private static final String GRADUAL = "DemoService.process";

    @Autowired private MockMvc mvc;
    @Autowired private CircuitBreakerRegistry registry;
    @Autowired private CircuitBreakerConfigurationProperties properties;
    @Autowired private MeterRegistry meters;

    private CircuitBreaker standard;
    private CircuitBreaker gradual;

    @BeforeEach
    void resetBothModes() {
        standard = registry.circuitBreaker(STANDARD);
        gradual = registry.circuitBreaker(GRADUAL);
        standard.reset();
        gradual.reset();
    }

    @Test
    void defaultConfigurationIsStandardAndOnlyTheNamedInstanceOptsIn() {
        assertThat(properties.getConfigs().get("default").getHalfOpenRecovery()).isNull();
        assertThat(standard.getCircuitBreakerConfig().getHalfOpenRecoveryConfig()).isNull();
        assertThat(registry.circuitBreaker("unconfiguredBackend").getCircuitBreakerConfig()
            .getHalfOpenRecoveryConfig()).isNull();
        assertThat(gradual.getCircuitBreakerConfig().getHalfOpenRecoveryConfig()).isNotNull();
        assertThat(gradual.getCircuitBreakerConfig().getHalfOpenRecoveryConfig()
            .isInitialTrafficPercentageAutomatic()).isTrue();
        assertThat(standard.getCircuitBreakerConfig().getPermittedNumberOfCallsInHalfOpenState()).isEqualTo(3);

        var names = CircuitBreakerMetricNames.ofDefaults();
        assertThat(meters.find(names.getRecoveryPhaseMetricName()).tag("name", STANDARD).gauge()).isNull();
        assertThat(meters.find(names.getRecoveryTrafficPercentageMetricName()).tag("name", STANDARD).gauge()).isNull();
        assertThat(meters.find(names.getAttemptsMetricName()).tag("name", STANDARD).functionCounter()).isNull();
        assertThat(meters.find(names.getPermittedAttemptsMetricName()).tag("name", STANDARD).functionCounter()).isNull();
        assertThat(meters.getMeters().stream().filter(meter -> STANDARD.equals(meter.getId().getTag("name")))).hasSize(16);
        assertThat(meters.getMeters().stream().filter(meter -> GRADUAL.equals(meter.getId().getTag("name")))).hasSize(20);
    }

    @Test
    void standardHalfOpenHasFiniteProbesAndClosesAfterTheirOutcomes() throws Exception {
        enterHalfOpen(standard);
        for (int i = 0; i < 3; i++) assertThat(standard.tryAcquirePermission()).isTrue();

        mvc.perform(get("/process-standard/100"))
            .andExpect(status().isInternalServerError())
            .andExpect(content().string(containsString("does not permit further calls")));

        assertThat(standard.getState()).isEqualTo(CircuitBreaker.State.HALF_OPEN);
        assertThat(standard.getMetrics().getNumberOfBufferedCalls()).isZero();
        assertThat(standard.getMetrics().getNumberOfNotPermittedCalls()).isEqualTo(1);
        assertThat(standard.getHalfOpenPhase().isActive()).isFalse();
        for (int i = 0; i < 3; i++) standard.onSuccess(0, TimeUnit.NANOSECONDS);
        assertThat(standard.getState()).isEqualTo(CircuitBreaker.State.CLOSED);

        mvc.perform(get("/process-standard/100"))
            .andExpect(status().isOk()).andExpect(content().string("OK"));
    }

    @Test
    void actualAopStandardClosesWithoutDwellWhileGradualKeepsItsPercentage() throws Exception {
        enterHalfOpen(standard);
        enterHalfOpen(gradual);
        for (int i = 0; i < 3; i++) {
            mvc.perform(get("/process-standard/100"))
                .andExpect(status().isOk()).andExpect(content().string("OK"));
        }
        assertThat(standard.getState()).isEqualTo(CircuitBreaker.State.CLOSED);

        int accepted = 0;
        for (int i = 0; i < 100; i++) {
            var response = mvc.perform(get("/process/100")).andReturn().getResponse();
            if (response.getStatus() == 200) accepted++;
            else assertThat(response.getStatus()).isEqualTo(500);
        }
        assertThat(accepted).isEqualTo(5);
        assertThat(gradual.getState()).isEqualTo(CircuitBreaker.State.HALF_OPEN);
        assertThat(gradual.getHalfOpenPhase().getPermittedTrafficPercentage()).isEqualTo(5);
        assertThat(standard.getState()).isEqualTo(CircuitBreaker.State.CLOSED);
    }

    @Test
    void healthUsesTheSamePublicHalfOpenStateForBothPolicies() throws Exception {
        enterHalfOpen(standard);
        enterHalfOpen(gradual);

        mvc.perform(get("/actuator/health"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.components.circuitBreakers.details['DemoService.processStandard'].details.state")
                .value("HALF_OPEN"))
            .andExpect(jsonPath("$.components.circuitBreakers.details['DemoService.process'].details.state")
                .value("HALF_OPEN"));
        assertThat(standard.getHalfOpenPhase()).isEqualTo(CircuitBreaker.HalfOpenPhase.inactive());
        assertThat(gradual.getHalfOpenPhase().isActive()).isTrue();
    }

    @Test
    void prometheusEndpointExportsBothModesAndTheGradualCountersAndTarget() throws Exception {
        enterHalfOpen(standard);
        enterHalfOpen(gradual);
        mvc.perform(get("/process-standard/100")).andExpect(status().isOk());
        mvc.perform(get("/process/100")).andExpect(status().isOk());

        String scrape = mvc.perform(get("/actuator/prometheus"))
            .andExpect(status().isOk())
            .andReturn().getResponse().getContentAsString();

        assertThat(scrape).containsPattern("(?m)^# TYPE resilience4j_circuitbreaker_admission_attempts(?:_total)? counter$");
        assertThat(scrape).containsPattern("(?m)^# TYPE resilience4j_circuitbreaker_admission_permitted_attempts(?:_total)? counter$");
        assertThat(scrape).containsPattern("(?m)^# TYPE resilience4j_circuitbreaker_recovery_traffic_percentage gauge$");
        assertThat(scrape.lines().filter(line -> line.startsWith("resilience4j_circuitbreaker_state{")
            && line.contains("name=\"" + STANDARD + "\"") && line.contains("state=\"half_open\"")))
            .allSatisfy(line -> assertThat(line).endsWith(" 1.0")).isNotEmpty();
        assertThat(scrape.lines().filter(line -> line.startsWith("resilience4j_circuitbreaker_state{")
            && line.contains("name=\"" + GRADUAL + "\"") && line.contains("state=\"half_open\"")))
            .allSatisfy(line -> assertThat(line).endsWith(" 1.0")).isNotEmpty();
        assertThat(scrape.lines().filter(line -> line.startsWith("resilience4j_circuitbreaker_recovery_traffic_percentage{")
            && line.contains("name=\"" + GRADUAL + "\"")))
            .allSatisfy(line -> assertThat(line).endsWith(" 5.0")).isNotEmpty();
        assertThat(scrape.lines().filter(line -> line.startsWith("resilience4j_circuitbreaker_admission_attempts_total{")
            && line.contains("name=\"" + GRADUAL + "\""))).isNotEmpty();
        assertThat(scrape.lines().filter(line -> line.startsWith("resilience4j_circuitbreaker_admission_permitted_attempts_total{")
            && line.contains("name=\"" + GRADUAL + "\""))).isNotEmpty();
        assertThat(scrape.lines().filter(line -> line.startsWith("resilience4j_circuitbreaker_admission_")
            && line.contains("name=\"" + STANDARD + "\""))).isEmpty();
    }

    @Test
    void bothRoutesShareValidationAndFallbackWithoutSharingCircuitState() throws Exception {
        long standardAttempts = standard.getAdmissionMetrics().getNumberOfAttempts();
        long gradualAttempts = gradual.getAdmissionMetrics().getNumberOfAttempts();
        for (String endpoint : new String[]{"/process-standard", "/process"}) {
            mvc.perform(get(endpoint + "/101")).andExpect(status().isBadRequest());
            mvc.perform(get(endpoint + "/no-number")).andExpect(status().isBadRequest());
        }
        assertThat(standard.getAdmissionMetrics().getNumberOfAttempts()).isEqualTo(standardAttempts);
        assertThat(gradual.getAdmissionMetrics().getNumberOfAttempts()).isEqualTo(gradualAttempts);

        mvc.perform(get("/process-standard/0"))
            .andExpect(status().isInternalServerError())
            .andExpect(content().string("Circuit breaker fallback response, caused by: [Forced failure]"));
        assertThat(standard.getMetrics().getNumberOfFailedCalls()).isEqualTo(1);
        assertThat(gradual.getMetrics().getNumberOfFailedCalls()).isZero();

        mvc.perform(get("/process/0"))
            .andExpect(status().isInternalServerError())
            .andExpect(content().string("Circuit breaker fallback response, caused by: [Forced failure]"));
        assertThat(standard.getMetrics().getNumberOfFailedCalls()).isEqualTo(1);
        assertThat(gradual.getMetrics().getNumberOfFailedCalls()).isEqualTo(1);
    }

    private static void enterHalfOpen(CircuitBreaker breaker) {
        breaker.transitionToOpenState();
        breaker.transitionToHalfOpenState();
    }
}
