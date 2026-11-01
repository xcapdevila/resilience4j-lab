package io.capdevila.poc.sr4j.lab;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import io.github.resilience4j.circuitbreaker.HalfOpenRecoveryConfig;
import java.util.List;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class LabSettingsTests {
    private final ObjectMapper mapper = new ObjectMapper();
    private final LabSettings defaults = LabSettings.from(
        CircuitBreaker.of("smart", CircuitBreakerConfig.custom()
            .halfOpenRecoveryConfig(HalfOpenRecoveryConfig.custom().build()).build()),
        CircuitBreaker.ofDefaults("standard"));

    @Test
    void trafficAndBackendPatchesPreserveTheNativeRecoveryPolicy() {
        var request = required().put("requestsPerSecond", 3).put("maxConcurrentRequests", 7)
            .put("backendSuccessRatio", 95).put("backendLatencyMillis", 50)
            .put("slowCallPercentage", 20).put("slowExtraLatencyMillis", 200)
            .put("healthyDurationMillis", 1_000).put("restartTrial", false);
        var changed = LabSettings.patch(request, defaults);
        assertThat(changed.requestsPerSecond()).isEqualTo(3);
        assertThat(changed.backendSuccessRatio()).isEqualTo(95);
        assertThat(changed.slowCallPercentage()).isEqualTo(20);
        assertThat(changed.recoveryDiffers(defaults)).isFalse();
        assertThat(changed.sharedDiffers(defaults)).isFalse();
        assertThat(LabSettings.restartTrial(request)).isFalse();
        assertThat(defaults.requestsPerSecond()).isEqualTo(100);
    }

    @Test
    void crossFieldValidationRejectsImpossibleWindowAndLatencyCombinations() {
        assertThatThrownBy(() -> LabSettings.patch(required().put("slidingWindowSize", 10)
            .put("closedMinimumNumberOfCalls", 11), defaults)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> LabSettings.patch(required().put("backendLatencyMillis", 3_000)
            .put("slowExtraLatencyMillis", 3_000), defaults)).isInstanceOf(IllegalArgumentException.class);
        var valid = LabSettings.patch(required().put("slidingWindowSize", 11)
            .put("closedMinimumNumberOfCalls", 11).put("backendLatencyMillis", 3_000)
            .put("slowExtraLatencyMillis", 2_000), defaults);
        assertThat(valid.closedMinimumNumberOfCalls()).isEqualTo(11);
        assertThat(valid.backendLatencyMillis() + valid.slowExtraLatencyMillis()).isEqualTo(5_000);
    }

    @Test
    void importedSettingsHaveStrictTypesAndBoundedRatesThresholdsAndTimers() throws Exception {
        for (String fragment : List.of(
            "\"requestsPerSecond\":-1", "\"requestsPerSecond\":1001", "\"requestsPerSecond\":1.5",
            "\"requestsPerSecond\":\"10\"", "\"requestsPerSecond\":null", "\"requestsPerSecond\":true",
            "\"maxConcurrentRequests\":0", "\"maxConcurrentRequests\":65",
            "\"initialTrafficPercentage\":101", "\"failureRateThreshold\":0",
            "\"slowCallRateThreshold\":100.1", "\"recoveryMaxFailureRateForAdvance\":-1",
            "\"recoveryFailureRateThreshold\":0", "\"recoveryMaxSlowCallRateForAdvance\":101",
            "\"recoverySlowCallRateThreshold\":100.1", "\"recoveryFailureRateThreshold\":\"20\"", "\"waitDurationInOpenStateMillis\":0",
            "\"maxWaitDurationInHalfOpenStateMillis\":-1", "\"backendSuccessRatio\":101",
            "\"slowCallPercentage\":-1", "\"healthyDurationMillis\":99",
            "\"automaticTransitionFromOpenToHalfOpenEnabled\":1", "\"restartTrial\":\"false\"",
            "\"unknownSetting\":123")) {
            ObjectNode request = required();
            request.setAll((ObjectNode) mapper.readTree("{" + fragment + "}"));
            assertThatThrownBy(() -> LabSettings.patch(request, defaults)).as(fragment)
                .isInstanceOf(IllegalArgumentException.class);
        }
    }

    @Test
    void nativePolicyAndSharedPolicyChangesAreClassifiedSeparately() {
        var recovery = LabSettings.patch(required().put("initialTrafficPercentage", 25), defaults);
        assertThat(recovery.recoveryDiffers(defaults)).isTrue();
        assertThat(recovery.sharedDiffers(defaults)).isFalse();
        var shared = LabSettings.patch(required().put("failureRateThreshold", 25.5)
            .put("maxWaitDurationInHalfOpenStateMillis", 0), defaults);
        assertThat(shared.sharedDiffers(defaults)).isTrue();
        assertThat(shared.recoveryDiffers(defaults)).isFalse();
        assertThat(shared.failureRateThreshold()).isEqualTo(25.5);
        assertThat(LabSettings.restartTrial(required())).isTrue();
        assertThat(LabSettings.patch(required().put("requestsPerSecond", 0), defaults).requestsPerSecond()).isZero();
    }

    @Test
    void recoveryHealthBandsAreIndependentAndAcceptZeroOnlyAsAnAdvanceMaximum() {
        var changed = LabSettings.patch(required().put("recoveryMaxFailureRateForAdvance", 0)
            .put("recoveryFailureRateThreshold", 10).put("recoveryMaxSlowCallRateForAdvance", 5)
            .put("recoverySlowCallRateThreshold", 25), defaults);
        assertThat(changed.recoveryDiffers(defaults)).isTrue();
        assertThat(changed.sharedDiffers(defaults)).isFalse();
        assertThat(changed.recoveryMaxFailureRateForAdvance()).isZero();
        assertThat(changed.recoveryFailureRateThreshold()).isEqualTo(10);
        assertThat(changed.failureRateThreshold()).isEqualTo(defaults.failureRateThreshold());
        assertThat(changed.slowCallRateThreshold()).isEqualTo(defaults.slowCallRateThreshold());
        for (var invalid : List.of(
            required().put("recoveryMaxFailureRateForAdvance", 20),
            required().put("recoveryMaxFailureRateForAdvance", 21),
            required().put("recoveryMaxSlowCallRateForAdvance", 50),
            required().put("recoverySlowCallRateThreshold", 10),
            required().put("recoveryMaxFailureRateForAdvance", 5.0000001)
                .put("recoveryFailureRateThreshold", 5.0000002))) {
            assertThatThrownBy(() -> LabSettings.patch(invalid, defaults)).isInstanceOf(IllegalArgumentException.class);
        }
    }

    private ObjectNode required() {
        return mapper.createObjectNode().put("trafficPercentageStep", defaults.trafficPercentageStep())
            .put("minimumPhaseDurationMillis", defaults.minimumPhaseDurationMillis());
    }
}
