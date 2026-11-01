package io.capdevila.poc.sr4j.lab;

import tools.jackson.databind.JsonNode;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import java.lang.reflect.RecordComponent;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;

/** Immutable, bounded settings for this local development tool. */
public record LabSettings(
    int trafficPercentageStep, long minimumPhaseDurationMillis, int minimumNumberOfCalls, String failureStrategy,
    int requestsPerSecond, int maxConcurrentRequests, int initialTrafficPercentage,
    double recoveryMaxFailureRateForAdvance, double recoveryMaxSlowCallRateForAdvance,
    double recoveryFailureRateThreshold, double recoverySlowCallRateThreshold,
    double failureRateThreshold, double slowCallRateThreshold, long slowCallDurationThresholdMillis,
    long waitDurationInOpenStateMillis, long maxWaitDurationInHalfOpenStateMillis,
    int slidingWindowSize, int closedMinimumNumberOfCalls, int standardHalfOpenPermittedCalls,
    boolean automaticTransitionFromOpenToHalfOpenEnabled,
    int backendSuccessRatio, int outageSuccessRatio, int recoveryFailureSuccessRatio,
    int backendLatencyMillis, int slowCallPercentage, int slowExtraLatencyMillis,
    long healthyDurationMillis, long outageDurationMillis, long recoveryHoldDurationMillis) {

    public LabSettings {
        range(trafficPercentageStep, 1, 100, "trafficPercentageStep");
        range(minimumPhaseDurationMillis, 100, 3_600_000, "minimumPhaseDurationMillis");
        range(minimumNumberOfCalls, 1, 100_000, "minimumNumberOfCalls");
        if (!Set.of("OPEN", "STEP_BACK").contains(failureStrategy)) throw invalid("failureStrategy must be OPEN or STEP_BACK");
        range(requestsPerSecond, 0, 1_000, "requestsPerSecond");
        range(maxConcurrentRequests, 1, 64, "maxConcurrentRequests");
        range(initialTrafficPercentage, 0, 100, "initialTrafficPercentage");
        recoveryThresholds(recoveryMaxFailureRateForAdvance, recoveryFailureRateThreshold, "failure");
        recoveryThresholds(recoveryMaxSlowCallRateForAdvance, recoverySlowCallRateThreshold, "slow-call");
        range(failureRateThreshold, 0.1, 100, "failureRateThreshold");
        range(slowCallRateThreshold, 0.1, 100, "slowCallRateThreshold");
        range(slowCallDurationThresholdMillis, 1, 60_000, "slowCallDurationThresholdMillis");
        range(waitDurationInOpenStateMillis, 100, 60_000, "waitDurationInOpenStateMillis");
        range(maxWaitDurationInHalfOpenStateMillis, 0, 3_600_000, "maxWaitDurationInHalfOpenStateMillis");
        range(slidingWindowSize, 1, 100_000, "slidingWindowSize");
        range(closedMinimumNumberOfCalls, 1, slidingWindowSize, "closedMinimumNumberOfCalls");
        range(standardHalfOpenPermittedCalls, 1, 100_000, "standardHalfOpenPermittedCalls");
        range(backendSuccessRatio, 0, 100, "backendSuccessRatio");
        range(outageSuccessRatio, 0, 100, "outageSuccessRatio");
        range(recoveryFailureSuccessRatio, 0, 100, "recoveryFailureSuccessRatio");
        range(backendLatencyMillis, 0, 5_000, "backendLatencyMillis");
        range(slowCallPercentage, 0, 100, "slowCallPercentage");
        range(slowExtraLatencyMillis, 0, 5_000, "slowExtraLatencyMillis");
        if (backendLatencyMillis + slowExtraLatencyMillis > 5_000) throw invalid("Combined backend latency must not exceed 5,000 ms");
        range(healthyDurationMillis, 100, 3_600_000, "healthyDurationMillis");
        range(outageDurationMillis, 100, 3_600_000, "outageDurationMillis");
        range(recoveryHoldDurationMillis, 0, 3_600_000, "recoveryHoldDurationMillis");
    }

    public static LabSettings from(CircuitBreaker smart, CircuitBreaker standard) {
        var config = smart.getCircuitBreakerConfig();
        var recovery = config.getHalfOpenRecoveryConfig();
        return new LabSettings(recovery.getTrafficPercentageStep(), recovery.getMinimumPhaseDuration().toMillis(),
            recovery.getMinimumNumberOfCalls(), recovery.getFailureStrategy().name(), 100, 16,
            recovery.isInitialTrafficPercentageAutomatic() ? 0 : recovery.getInitialTrafficPercentage(),
            recovery.getMaxFailureRateForAdvance(), recovery.getMaxSlowCallRateForAdvance(),
            recovery.getFailureRateThreshold(), recovery.getSlowCallRateThreshold(),
            config.getFailureRateThreshold(), config.getSlowCallRateThreshold(), config.getSlowCallDurationThreshold().toMillis(),
            config.getWaitIntervalFunctionInOpenState().apply(1), config.getMaxWaitDurationInHalfOpenState().toMillis(),
            config.getSlidingWindowSize(), config.getMinimumNumberOfCalls(),
            standard.getCircuitBreakerConfig().getPermittedNumberOfCallsInHalfOpenState(),
            config.isAutomaticTransitionFromOpenToHalfOpenEnabled(), 100, 0, 0, 0, 0, 1_000, 8_000, 8_000, 55_000);
    }

    /** Accept only exact JSON types; invalid requests never mutate live settings. */
    public static LabSettings patch(JsonNode request, LabSettings current) {
        if (request == null || !request.isObject()) throw invalid("Settings must be a JSON object");
        if (!request.has("trafficPercentageStep") || !request.has("minimumPhaseDurationMillis"))
            throw invalid("Provide trafficPercentageStep and minimumPhaseDurationMillis");
        restartTrial(request);
        try {
            RecordComponent[] components = LabSettings.class.getRecordComponents();
            Map<String, RecordComponent> fields = new HashMap<>();
            for (var component : components) fields.put(component.getName(), component);
            request.propertyNames().forEach(name -> {
                if (!fields.containsKey(name) && !Set.of("restartTrial", "revision", "trialRevision", "instanceId", "trialStartedAtEpochMillis").contains(name))
                    throw invalid("Unknown setting: " + name);
            });
            Object[] values = new Object[components.length];
            Class<?>[] types = new Class<?>[components.length];
            for (int index = 0; index < components.length; index++) {
                var component = components[index];
                types[index] = component.getType();
                if (!request.has(component.getName())) values[index] = component.getAccessor().invoke(current);
                else values[index] = exactValue(component.getName(), types[index], request.get(component.getName()));
            }
            return LabSettings.class.getDeclaredConstructor(types).newInstance(values);
        } catch (java.lang.reflect.InvocationTargetException error) {
            if (error.getCause() instanceof IllegalArgumentException invalid) throw invalid;
            throw new IllegalStateException(error.getCause());
        } catch (ReflectiveOperationException error) {
            throw new IllegalStateException(error);
        }
    }

    private static Object exactValue(String name, Class<?> type, JsonNode value) {
        if (type == int.class && value.isIntegralNumber() && value.canConvertToInt()) return value.intValue();
        if (type == long.class && value.isIntegralNumber() && value.canConvertToLong()) return value.longValue();
        if (type == double.class && value.isNumber() && Double.isFinite(value.doubleValue())) return value.doubleValue();
        if (type == boolean.class && value.isBoolean()) return value.booleanValue();
        if (type == String.class && value.isString()) return value.stringValue();
        throw invalid("Invalid JSON type for " + name);
    }

    public static boolean restartTrial(JsonNode request) {
        if (!request.has("restartTrial")) return true;
        if (!request.get("restartTrial").isBoolean()) throw invalid("restartTrial must be boolean");
        return request.get("restartTrial").booleanValue();
    }

    public boolean recoveryDiffers(LabSettings other) {
        return trafficPercentageStep != other.trafficPercentageStep || minimumPhaseDurationMillis != other.minimumPhaseDurationMillis
            || minimumNumberOfCalls != other.minimumNumberOfCalls || !failureStrategy.equals(other.failureStrategy)
            || initialTrafficPercentage != other.initialTrafficPercentage
            || recoveryMaxFailureRateForAdvance != other.recoveryMaxFailureRateForAdvance
            || recoveryMaxSlowCallRateForAdvance != other.recoveryMaxSlowCallRateForAdvance
            || recoveryFailureRateThreshold != other.recoveryFailureRateThreshold
            || recoverySlowCallRateThreshold != other.recoverySlowCallRateThreshold;
    }

    public boolean sharedDiffers(LabSettings other) {
        return failureRateThreshold != other.failureRateThreshold || slowCallRateThreshold != other.slowCallRateThreshold
            || slowCallDurationThresholdMillis != other.slowCallDurationThresholdMillis
            || waitDurationInOpenStateMillis != other.waitDurationInOpenStateMillis
            || maxWaitDurationInHalfOpenStateMillis != other.maxWaitDurationInHalfOpenStateMillis
            || slidingWindowSize != other.slidingWindowSize || closedMinimumNumberOfCalls != other.closedMinimumNumberOfCalls
            || standardHalfOpenPermittedCalls != other.standardHalfOpenPermittedCalls
            || automaticTransitionFromOpenToHalfOpenEnabled != other.automaticTransitionFromOpenToHalfOpenEnabled;
    }

    private static void recoveryThresholds(double advance, double regression, String name) {
        range(advance, 0, 100, name + " recovery advance maximum");
        range(regression, 0, 100, name + " recovery regression threshold");
        // Native thresholds are floats; reject a double pair that collapses to the
        // same native value before draining traffic or constructing a replacement.
        if ((float) advance >= (float) regression) throw invalid(name + " recovery advance maximum must be below its regression threshold");
    }

    private static void range(double value, double min, double max, String name) {
        if (!Double.isFinite(value) || value < min || value > max) throw invalid(name + " must be between " + min + " and " + max);
    }
    private static IllegalArgumentException invalid(String message) { return new IllegalArgumentException(message); }
}
