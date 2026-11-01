package io.capdevila.poc.sr4j;

import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.security.SecureRandom;
import java.util.Objects;
import java.util.Random;

@Slf4j
@Service
public class DemoService {

    static final String INVALID_SUCCESS_RATIO = "successRatio must be an integer between 0 and 100";
    static final String INVALID_SIMULATION = "Latency values must be between 0 and 5000 ms, their sum must not exceed 5000 ms, and slowCallPercentage must be between 0 and 100";

    private final Random random;

    public DemoService() {
        this(new SecureRandom());
    }

    DemoService(Random random) {
        this.random = Objects.requireNonNull(random);
    }

    @CircuitBreaker(name = "DemoService.process", fallbackMethod = "fallback")
    String process(int successRatio) {
        return simulateBackend(successRatio);
    }

    @CircuitBreaker(name = "DemoService.processStandard", fallbackMethod = "fallback")
    String processStandard(int successRatio) {
        return simulateBackend(successRatio);
    }

    @CircuitBreaker(name = "DemoService.process", fallbackMethod = "fallback")
    String process(int successRatio, int latencyMillis, int slowCallPercentage, int slowExtraLatencyMillis) {
        simulateLatency(latencyMillis, slowCallPercentage, slowExtraLatencyMillis);
        return simulateBackend(successRatio);
    }

    @CircuitBreaker(name = "DemoService.processStandard", fallbackMethod = "fallback")
    String processStandard(int successRatio, int latencyMillis, int slowCallPercentage, int slowExtraLatencyMillis) {
        simulateLatency(latencyMillis, slowCallPercentage, slowExtraLatencyMillis);
        return simulateBackend(successRatio);
    }

    static boolean validSimulation(int latencyMillis, int slowCallPercentage, int slowExtraLatencyMillis) {
        return latencyMillis >= 0 && latencyMillis <= 5000 && slowExtraLatencyMillis >= 0
            && slowExtraLatencyMillis <= 5000 && latencyMillis + slowExtraLatencyMillis <= 5000
            && slowCallPercentage >= 0 && slowCallPercentage <= 100;
    }

    private void simulateLatency(int latencyMillis, int slowCallPercentage, int slowExtraLatencyMillis) {
        if (!validSimulation(latencyMillis, slowCallPercentage, slowExtraLatencyMillis)) {
            throw new IllegalArgumentException(INVALID_SIMULATION);
        }
        boolean slow = slowCallPercentage == 100 || (slowCallPercentage > 0 && random.nextInt(100) < slowCallPercentage);
        int delay = latencyMillis + (slow ? slowExtraLatencyMillis : 0);
        if (delay == 0) return;
        try {
            // The delay is inside the decorated call, so native slow-call and timer
            // metrics include it for both successful and failed backend outcomes.
            Thread.sleep(delay);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Simulated service call interrupted", interrupted);
        }
    }

    private String simulateBackend(int successRatio) {
        if (successRatio < 0 || successRatio > 100) {
            throw new IllegalArgumentException(INVALID_SUCCESS_RATIO);
        }
        if (successRatio == 0) {
            log.error("Forced failure");
            throw new RuntimeException("Forced failure");
        }
        if (random.nextInt(100) >= successRatio) {
            log.error("Random failure");
            throw new RuntimeException("Random failure");
        }
        log.info("OK");
        return "OK";
    }

    String fallback(Throwable throwable) {
        return String.format("Circuit breaker fallback response, caused by: [%s]", throwable.getMessage());
    }

}
