package io.capdevila.poc.sr4j;

import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.common.CompositeCustomizer;
import io.github.resilience4j.common.circuitbreaker.configuration.CircuitBreakerConfigCustomizer;
import io.github.resilience4j.springboot.circuitbreaker.autoconfigure.CircuitBreakerProperties;
import java.util.Collections;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;

import static org.assertj.core.api.Assertions.assertThat;

class HalfOpenRecoveryBindingTests {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
        .withUserConfiguration(RecoveryBindingConfiguration.class)
        .withPropertyValues("resilience4j.circuitbreaker.instances.bound.halfOpenRecovery.enabled=true");

    @ParameterizedTest
    @ValueSource(ints = {30, 45, 100})
    void automaticInitialPercentageUsesTheStepBoundBySpring(int step) {
        contextRunner.withPropertyValues(
            "resilience4j.circuitbreaker.instances.bound.halfOpenRecovery.trafficPercentageStep=" + step)
            .run(context -> {
                assertThat(context).hasNotFailed();
                var breaker = context.getBean(CircuitBreaker.class);
                var recovery = breaker.getCircuitBreakerConfig().getHalfOpenRecoveryConfig();
                assertThat(recovery.isInitialTrafficPercentageAutomatic()).isTrue();
                assertThat(recovery.getInitialTrafficPercentage()).isEqualTo(step);

                breaker.transitionToOpenState();
                breaker.transitionToHalfOpenState();

                assertThat(breaker.getState()).isEqualTo(CircuitBreaker.State.HALF_OPEN);
                assertThat(breaker.getHalfOpenPhase().getPermittedTrafficPercentage()).isEqualTo(step);
            });
    }

    @Test
    void explicitInitialPercentageRemainsAnAdvancedOverride() {
        contextRunner.withPropertyValues(
            "resilience4j.circuitbreaker.instances.bound.halfOpenRecovery.trafficPercentageStep=30",
            "resilience4j.circuitbreaker.instances.bound.halfOpenRecovery.initialTrafficPercentage=5")
            .run(context -> {
                assertThat(context).hasNotFailed();
                var recovery = context.getBean(CircuitBreaker.class)
                    .getCircuitBreakerConfig().getHalfOpenRecoveryConfig();
                assertThat(recovery.isInitialTrafficPercentageAutomatic()).isFalse();
                assertThat(recovery.getInitialTrafficPercentage()).isEqualTo(5);
            });
    }

    @Test
    void explicitZeroSelectsAutomaticInitialPercentage() {
        contextRunner.withPropertyValues(
            "resilience4j.circuitbreaker.instances.bound.halfOpenRecovery.trafficPercentageStep=45",
            "resilience4j.circuitbreaker.instances.bound.halfOpenRecovery.initialTrafficPercentage=0")
            .run(context -> {
                assertThat(context).hasNotFailed();
                var recovery = context.getBean(CircuitBreaker.class)
                    .getCircuitBreakerConfig().getHalfOpenRecoveryConfig();
                assertThat(recovery.isInitialTrafficPercentageAutomatic()).isTrue();
                assertThat(recovery.getInitialTrafficPercentage()).isEqualTo(45);
            });
    }

    @Test
    void invalidStepFailsStartupInsteadOfBeingClamped() {
        contextRunner.withPropertyValues(
            "resilience4j.circuitbreaker.instances.bound.halfOpenRecovery.trafficPercentageStep=120")
            .run(context -> {
                assertThat(context).hasFailed();
                assertThat(context.getStartupFailure())
                    .hasRootCauseInstanceOf(IllegalArgumentException.class)
                    .hasStackTraceContaining("trafficPercentageStep must be between 1 and 100");
            });
    }

    @Test
    void recoveryHealthBandsBindIndependentlyOfOrdinaryThresholds() {
        contextRunner.withPropertyValues(
            "resilience4j.circuitbreaker.instances.bound.failureRateThreshold=60",
            "resilience4j.circuitbreaker.instances.bound.slowCallRateThreshold=80",
            "resilience4j.circuitbreaker.instances.bound.halfOpenRecovery.maxFailureRateForAdvance=0",
            "resilience4j.circuitbreaker.instances.bound.halfOpenRecovery.failureRateThreshold=12.5",
            "resilience4j.circuitbreaker.instances.bound.halfOpenRecovery.maxSlowCallRateForAdvance=5",
            "resilience4j.circuitbreaker.instances.bound.halfOpenRecovery.slowCallRateThreshold=25")
            .run(context -> {
                assertThat(context).hasNotFailed();
                var config = context.getBean(CircuitBreaker.class).getCircuitBreakerConfig();
                var recovery = config.getHalfOpenRecoveryConfig();
                assertThat(config.getFailureRateThreshold()).isEqualTo(60);
                assertThat(config.getSlowCallRateThreshold()).isEqualTo(80);
                assertThat(recovery.getMaxFailureRateForAdvance()).isZero();
                assertThat(recovery.getFailureRateThreshold()).isEqualTo(12.5f);
                assertThat(recovery.getMaxSlowCallRateForAdvance()).isEqualTo(5);
                assertThat(recovery.getSlowCallRateThreshold()).isEqualTo(25);
            });
    }

    @Test
    void overlappingRecoveryHealthBandsFailStartupClearly() {
        contextRunner.withPropertyValues(
            "resilience4j.circuitbreaker.instances.bound.halfOpenRecovery.maxFailureRateForAdvance=20",
            "resilience4j.circuitbreaker.instances.bound.halfOpenRecovery.failureRateThreshold=20")
            .run(context -> {
                assertThat(context).hasFailed();
                assertThat(context.getStartupFailure()).hasRootCauseInstanceOf(IllegalArgumentException.class);
            });
    }

    @TestConfiguration(proxyBeanMethods = false)
    @EnableConfigurationProperties(CircuitBreakerProperties.class)
    static class RecoveryBindingConfiguration {

        @Bean
        CircuitBreaker boundCircuitBreaker(CircuitBreakerProperties properties) {
            return CircuitBreaker.of("bound", properties.createCircuitBreakerConfig("bound",
                properties.getInstances().get("bound"),
                new CompositeCustomizer<CircuitBreakerConfigCustomizer>(Collections.emptyList())));
        }
    }
}
