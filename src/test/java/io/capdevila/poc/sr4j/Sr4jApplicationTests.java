package io.capdevila.poc.sr4j;

import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import io.github.resilience4j.common.circuitbreaker.configuration.CircuitBreakerConfigCustomizer;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.test.web.servlet.MockMvc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(properties = {
    "resilience4j.circuitbreaker.configs.default.automaticTransitionFromOpenToHalfOpenEnabled=false",
    "resilience4j.circuitbreaker.configs.default.maxWaitDurationInHalfOpenState=0"
})
@AutoConfigureMockMvc
@Import(Sr4jApplicationTests.DeterministicClockConfiguration.class)
class Sr4jApplicationTests {

    private static final Instant TEST_INSTANT = Instant.parse("2026-01-01T00:00:00Z");

    @Autowired
    private MockMvc mvc;

    @Autowired
    private CircuitBreakerRegistry registry;

    private CircuitBreaker circuitBreaker;

    @BeforeEach
    void resetCircuitBreaker() {
        circuitBreaker = registry.circuitBreaker("DemoService.process");
        circuitBreaker.reset();
        assertThat(circuitBreaker.getCircuitBreakerConfig().getClock().instant()).isEqualTo(TEST_INSTANT);
    }

    @Test
    void fullSuccessReturnsOkAndRecordsSuccess() throws Exception {
        mvc.perform(get("/process/100"))
            .andExpect(status().isOk())
            .andExpect(content().string("OK"));

        assertThat(circuitBreaker.getMetrics().getNumberOfSuccessfulCalls()).isEqualTo(1);
        assertThat(circuitBreaker.getMetrics().getNumberOfFailedCalls()).isZero();
    }

    @Test
    void forcedBackendFailureUsesTheActualCircuitBreakerFallback() throws Exception {
        mvc.perform(get("/process/0"))
            .andExpect(status().isInternalServerError())
            .andExpect(content().string("Circuit breaker fallback response, caused by: [Forced failure]"));

        assertThat(circuitBreaker.getMetrics().getNumberOfFailedCalls()).isEqualTo(1);
    }

    @Test
    void anOpenCircuitRejectsTheCallAndUsesFallback() throws Exception {
        circuitBreaker.transitionToOpenState();

        mvc.perform(get("/process/100"))
            .andExpect(status().isInternalServerError())
            .andExpect(content().string(containsString("Circuit breaker fallback response, caused by:")))
            .andExpect(content().string(containsString("does not permit further calls")));

        assertThat(circuitBreaker.getState()).isEqualTo(CircuitBreaker.State.OPEN);
        assertThat(circuitBreaker.getMetrics().getNumberOfNotPermittedCalls()).isEqualTo(1);
        assertThat(circuitBreaker.getMetrics().getNumberOfSuccessfulCalls()).isZero();
    }

    @Test
    void invalidInputDoesNotReachTheCircuitBreaker() throws Exception {
        mvc.perform(get("/process/101")).andExpect(status().isBadRequest());
        mvc.perform(get("/process/no-number")).andExpect(status().isBadRequest());

        assertThat(circuitBreaker.getMetrics().getNumberOfBufferedCalls()).isZero();
        assertThat(circuitBreaker.getMetrics().getNumberOfNotPermittedCalls()).isZero();
        assertThat(circuitBreaker.getState()).isEqualTo(CircuitBreaker.State.CLOSED);
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class DeterministicClockConfiguration {

        @Bean
        CircuitBreakerConfigCustomizer deterministicCircuitBreakerClock() {
            return CircuitBreakerConfigCustomizer.of("default",
                builder -> builder.clock(Clock.fixed(TEST_INSTANT, ZoneOffset.UTC)));
        }
    }

}
