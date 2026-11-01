package io.capdevila.poc.sr4j.lab;

import com.fasterxml.jackson.annotation.JsonUnwrapped;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.JsonNodeFactory;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import io.github.resilience4j.circuitbreaker.HalfOpenRecoveryConfig;
import io.micrometer.core.instrument.FunctionCounter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.annotation.PreDestroy;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.ReentrantLock;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.boot.web.server.servlet.context.ServletWebServerApplicationContext;
import org.springframework.context.annotation.Profile;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;

/** Local development traffic: bounded real HTTP calls, with no queued load or forced runtime transitions. */
@Service
@Profile("lab")
public class LabTrafficService {
    static final String SMART = "DemoService.process";
    static final String STANDARD = "DemoService.processStandard";
    private final CircuitBreakerRegistry registry;
    private volatile Run run;
    private final String instanceId = UUID.randomUUID().toString();
    private final ReentrantLock trafficControl = new ReentrantLock();
    private final boolean autostart;
    private final String contextPath;
    private final LabScenario scenario = new LabScenario();
    private final TrafficPacer pacer = new TrafficPacer();
    private final ArrayDeque<LabEvent> events = new ArrayDeque<>();
    private final LoadCounters smartLoad = new LoadCounters();
    private final LoadCounters standardLoad = new LoadCounters();
    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor(
        runnable -> new Thread(runnable, "lab-traffic-tick"));
    private final ExecutorService httpWorkers = Executors.newFixedThreadPool(8,
        runnable -> new Thread(runnable, "lab-http"));
    private final HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2))
        .executor(httpWorkers).version(HttpClient.Version.HTTP_1_1).build();
    private volatile String baseUrl;

    public LabTrafficService(CircuitBreakerRegistry registry, MeterRegistry meters,
        @Value("${lab.autostart:true}") boolean autostart,
        @Value("${server.servlet.context-path:}") String contextPath) {
        this.registry = registry;
        var smart = registry.circuitBreaker(SMART);
        var standard = registry.circuitBreaker(STANDARD);
        this.run = new Run(smart, standard, LabSettings.from(smart, standard), 0, 0, System.currentTimeMillis());
        this.autostart = autostart;
        this.contextPath = contextPath;
        bindEvents(smart);
        bindEvents(standard);
        bindLoadMetrics(meters, SMART, smartLoad);
        bindLoadMetrics(meters, STANDARD, standardLoad);
    }

    private void bindLoadMetrics(MeterRegistry meters, String name, LoadCounters load) {
        Gauge.builder("lab.traffic.requested", this, service -> service.run.settings().requestsPerSecond()).tag("name", name).register(meters);
        Gauge.builder("lab.traffic.in.flight", load, value -> value.inFlight.get()).tag("name", name).register(meters);
        FunctionCounter.builder("lab.traffic.dispatched", load, value -> value.dispatched.get()).tag("name", name).register(meters);
        FunctionCounter.builder("lab.traffic.completed", load, value -> value.completed.get()).tag("name", name).register(meters);
        FunctionCounter.builder("lab.traffic.skipped", load, value -> value.skipped.get()).tag("name", name).register(meters);
    }

    private void bindEvents(CircuitBreaker breaker) {
        breaker.getEventPublisher().onStateTransition(event -> {
            if (!isCurrent(breaker)) return;
            if (breaker == run.smart() && event.getStateTransition().getToState() != CircuitBreaker.State.HALF_OPEN)
                smartLoad.phaseStartedNanos = 0;
            addCurrentEvent(breaker, new LabEvent(Instant.now().toString(), breaker.getName(), "STATE_TRANSITION",
                event.getStateTransition().getFromState().name(), event.getStateTransition().getToState().name(), ""));
        });
        breaker.getEventPublisher().onHalfOpenPhaseTransition(event -> {
            if (!isCurrent(breaker)) return;
            if (breaker == run.smart()) smartLoad.phaseStartedNanos = System.nanoTime();
            addCurrentEvent(breaker, new LabEvent(Instant.now().toString(), breaker.getName(), "HALF_OPEN_PHASE_TRANSITION",
                Integer.toString(event.getPreviousPhase().getPermittedTrafficPercentage()),
                Integer.toString(event.getPhase().getPermittedTrafficPercentage()), event.getReason().name()));
        });
    }

    private boolean isCurrent(CircuitBreaker breaker) {
        var current = run;
        return breaker == current.smart() || breaker == current.standard();
    }

    private synchronized void addCurrentEvent(CircuitBreaker breaker, LabEvent event) {
        if (isCurrent(breaker)) addEvent(event);
    }

    @EventListener(ApplicationReadyEvent.class)
    public void ready(ApplicationReadyEvent event) {
        if (!(event.getApplicationContext() instanceof ServletWebServerApplicationContext web)) return;
        baseUrl = "http://127.0.0.1:" + web.getWebServer().getPort() + contextPath;
        if (autostart) select("cycle");
        scheduler.scheduleWithFixedDelay(this::tick, 0, 10, TimeUnit.MILLISECONDS);
    }

    private void tick() {
        if (!trafficControl.tryLock()) return;
        try {
            long now = System.nanoTime();
            smartLoad.measure(now);
            standardLoad.measure(now);
            var current = run;
            var settings = current.settings();
            var phase = current.smart().getHalfOpenPhase();
            var instruction = scenario.tick(System.currentTimeMillis(), phase.getPermittedTrafficPercentage(),
                current.smart().getState().name(), current.smart().getCircuitBreakerConfig().getHalfOpenRecoveryConfig().getInitialTrafficPercentage(), settings);
            int due = pacer.poll(now, settings.requestsPerSecond(), instruction.running());
            String parameters = "?latencyMillis=" + settings.backendLatencyMillis() + "&slowCallPercentage=" + settings.slowCallPercentage()
                + "&slowExtraLatencyMillis=" + settings.slowExtraLatencyMillis();
            for (int index = 0; index < due; index++) {
                send("/process/" + instruction.successRatio() + parameters, smartLoad, settings.maxConcurrentRequests());
                send("/process-standard/" + instruction.successRatio() + parameters, standardLoad, settings.maxConcurrentRequests());
            }
        } finally {
            trafficControl.unlock();
        }
    }

    private void send(String endpoint, LoadCounters load, int limit) {
        if (load.inFlight.get() >= limit) { load.skipped.incrementAndGet(); return; }
        load.inFlight.incrementAndGet();
        load.dispatched.incrementAndGet();
        try {
            var request = HttpRequest.newBuilder(URI.create(baseUrl + endpoint)).timeout(Duration.ofSeconds(10)).GET().build();
            client.sendAsync(request, HttpResponse.BodyHandlers.discarding()).whenComplete((response, error) -> {
                if (error != null) load.transportErrors.incrementAndGet();
                else load.completed.incrementAndGet();
                load.inFlight.decrementAndGet();
            });
        } catch (RuntimeException error) {
            load.transportErrors.incrementAndGet();
            load.inFlight.decrementAndGet();
        }
    }

    public LabStatus select(String name) {
        trafficControl.lock();
        try {
            scenario.select(name, System.currentTimeMillis());
            pacer.reset();
            return status();
        } finally { trafficControl.unlock(); }
    }

    public LabStatus configure(int step, long duration) { return configure(step, duration, null, null); }
    public LabStatus configure(int step, long duration, HalfOpenRecoveryConfig.FailureStrategy strategy) { return configure(step, duration, strategy, null); }
    public LabStatus configure(int step, long duration, HalfOpenRecoveryConfig.FailureStrategy strategy, Integer samples) {
        var request = JsonNodeFactory.instance.objectNode().put("trafficPercentageStep", step).put("minimumPhaseDurationMillis", duration);
        if (strategy != null) request.put("failureStrategy", strategy.name());
        if (samples != null) request.put("minimumNumberOfCalls", samples);
        return configure(request);
    }

    public LabStatus configure(JsonNode request) {
        trafficControl.lock();
        try {
            var previous = run;
            var next = LabSettings.patch(request, previous.settings());
            boolean restart = LabSettings.restartTrial(request);
            boolean sharedChanged = next.sharedDiffers(previous.settings());
            // The initial normal instance may have its own CLOSED minimum. A full
            // settings apply unifies explicitly selected common parameters.
            if (request.has("closedMinimumNumberOfCalls")
                && next.closedMinimumNumberOfCalls() != previous.standard().getCircuitBreakerConfig().getMinimumNumberOfCalls()) sharedChanged = true;
            if (!restart && (sharedChanged || next.recoveryDiffers(previous.settings())))
                throw new IllegalArgumentException("Recovery or shared circuit changes require restartTrial=true.");
            if (!restart) {
                run = new Run(previous.smart(), previous.standard(), next, previous.revision() + 1,
                    previous.trialRevision(), previous.trialStartedAtEpochMillis());
                if (next.requestsPerSecond() != previous.settings().requestsPerSecond()) pacer.reset();
                addEvent(new LabEvent(Instant.now().toString(), SMART, "LOAD_SETTINGS_CHANGED", "Live settings", "Traffic and simulated service updated", "LIVE_UPDATE"));
                return status();
            }
            awaitDrain();
            var smart = CircuitBreaker.of(SMART, sharedConfig(previous.smart().getCircuitBreakerConfig(), next)
                .halfOpenRecoveryConfig(HalfOpenRecoveryConfig.from(previous.smart().getCircuitBreakerConfig().getHalfOpenRecoveryConfig())
                    .initialTrafficPercentage(next.initialTrafficPercentage()).trafficPercentageStep(next.trafficPercentageStep())
                    .minimumPhaseDuration(Duration.ofMillis(next.minimumPhaseDurationMillis())).minimumNumberOfCalls(next.minimumNumberOfCalls())
                    .failureStrategy(HalfOpenRecoveryConfig.FailureStrategy.valueOf(next.failureStrategy()))
                    .maxFailureRateForAdvance((float) next.recoveryMaxFailureRateForAdvance())
                    .maxSlowCallRateForAdvance((float) next.recoveryMaxSlowCallRateForAdvance())
                    .failureRateThreshold((float) next.recoveryFailureRateThreshold())
                    .slowCallRateThreshold((float) next.recoverySlowCallRateThreshold()).build()).build(), previous.smart().getTags());
            var standard = sharedChanged ? CircuitBreaker.of(STANDARD,
                sharedConfig(previous.standard().getCircuitBreakerConfig(), next).halfOpenRecoveryConfig(null)
                    .permittedNumberOfCallsInHalfOpenState(next.standardHalfOpenPermittedCalls()).build(), previous.standard().getTags()) : previous.standard();
            bindEvents(smart);
            if (sharedChanged) bindEvents(standard);
            if (registry.replace(SMART, smart).isEmpty()) throw new ConfigurationBusyException();
            if (sharedChanged && registry.replace(STANDARD, standard).isEmpty()) throw new ConfigurationBusyException();
            run = new Run(smart, standard, next, previous.revision() + 1, previous.trialRevision() + 1,
                System.currentTimeMillis());
            smartLoad.phaseStartedNanos = 0;
            previous.smart().transitionToForcedOpenState();
            if (sharedChanged) previous.standard().transitionToForcedOpenState();
            addEvent(new LabEvent(Instant.now().toString(), SMART, "CONFIGURATION_CHANGED", summary(previous.settings()), summary(next), "NEW_RUN"));
            scenario.select(scenario.snapshot(next).scenario(), System.currentTimeMillis());
            pacer.reset();
            return status();
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new ConfigurationBusyException();
        } finally { trafficControl.unlock(); }
    }

    private static CircuitBreakerConfig.Builder sharedConfig(CircuitBreakerConfig previous, LabSettings next) {
        return CircuitBreakerConfig.from(previous).failureRateThreshold((float) next.failureRateThreshold())
            .slowCallRateThreshold((float) next.slowCallRateThreshold()).slowCallDurationThreshold(Duration.ofMillis(next.slowCallDurationThresholdMillis()))
            .waitDurationInOpenState(Duration.ofMillis(next.waitDurationInOpenStateMillis()))
            .maxWaitDurationInHalfOpenState(Duration.ofMillis(next.maxWaitDurationInHalfOpenStateMillis()))
            .slidingWindowType(CircuitBreakerConfig.SlidingWindowType.COUNT_BASED).slidingWindowSize(next.slidingWindowSize())
            .minimumNumberOfCalls(next.closedMinimumNumberOfCalls())
            .automaticTransitionFromOpenToHalfOpenEnabled(next.automaticTransitionFromOpenToHalfOpenEnabled());
    }

    private void awaitDrain() throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(12);
        while (smartLoad.inFlight.get() + standardLoad.inFlight.get() != 0) {
            if (System.nanoTime() >= deadline) throw new ConfigurationBusyException();
            Thread.sleep(5);
        }
    }

    public LabStatus status() {
        var current = run;
        var instruction = scenario.snapshot(current.settings());
        return new LabStatus(instruction.scenario(), instruction.stage(), instruction.successRatio(), instruction.running(),
            current.settings().requestsPerSecond(), smartLoad.dispatched.get() + standardLoad.dispatched.get(),
            smartLoad.completed.get() + standardLoad.completed.get(), smartLoad.transportErrors.get() + standardLoad.transportErrors.get(),
            smartLoad.skipped.get() + standardLoad.skipped.get(),
            List.of(snapshot(current.smart(), "GRADUAL", smartLoad, current), snapshot(current.standard(), "STANDARD", standardLoad, current)),
            eventSnapshot(), new RecoverySettings(current.settings(), current.revision(), current.trialRevision(),
                instanceId, current.trialStartedAtEpochMillis()));
    }

    private CircuitStatus snapshot(CircuitBreaker breaker, String mode, LoadCounters load, Run current) {
        var phase = breaker.getHalfOpenPhase();
        var admission = breaker.getAdmissionMetrics();
        boolean gradual = mode.equals("GRADUAL");
        Double remaining = null;
        if (gradual && phase.isActive() && load.phaseStartedNanos != 0 && current.settings().requestsPerSecond() > 0
            && scenario.snapshot(current.settings()).running() && load.observedRate > 0) {
            double samples = Math.max(0, current.settings().minimumNumberOfCalls() - breaker.getMetrics().getNumberOfBufferedCalls());
            double sampleMillis = 1_000d * samples / (load.observedRate * phase.getPermittedTrafficPercentage() / 100d);
            double dwellMillis = Math.max(0, current.settings().minimumPhaseDurationMillis() - (System.nanoTime() - load.phaseStartedNanos) / 1_000_000d);
            remaining = Math.max(dwellMillis, sampleMillis);
        }
        return new CircuitStatus(breaker.getName(), mode, breaker.getState().name(), phase.getPhase(), phase.getNumberOfPhases(),
            phase.isActive() ? phase.getPermittedTrafficPercentage() : null,
            gradual ? admission.getNumberOfAttempts() : null, gradual ? admission.getNumberOfPermittedAttempts() : null,
            breaker.getMetrics().getFailureRate(), breaker.getMetrics().getSlowCallRate(), breaker.getMetrics().getNumberOfBufferedCalls(), load.dispatched.get(),
            load.completed.get(), load.skipped.get(), load.inFlight.get(), load.observedRate, remaining);
    }

    private static String summary(LabSettings settings) {
        return settings.trafficPercentageStep() + "% / " + settings.minimumPhaseDurationMillis() + " ms / "
            + settings.minimumNumberOfCalls() + " samples / " + settings.failureStrategy()
            + " / advance failures <= " + settings.recoveryMaxFailureRateForAdvance() + "% and slow <= "
            + settings.recoveryMaxSlowCallRateForAdvance() + "% / regress failures >= " + settings.recoveryFailureRateThreshold()
            + "% or slow >= " + settings.recoverySlowCallRateThreshold() + "%";
    }
    private synchronized void addEvent(LabEvent event) { if (events.size() == 512) events.removeFirst(); events.addLast(event); }
    private synchronized List<LabEvent> eventSnapshot() { return List.copyOf(events); }

    @PreDestroy public void close() {
        trafficControl.lock();
        try { scenario.select("pause", System.currentTimeMillis()); scheduler.shutdownNow(); }
        finally { trafficControl.unlock(); }
        client.close(); httpWorkers.shutdownNow();
    }

    public record CircuitStatus(String name, String mode, String state, int phase, int numberOfPhases,
        Integer targetPercentage, Long attempts, Long permittedAttempts, float failureRate, float slowCallRate, int bufferedCalls,
        long dispatchedRequests, long completedRequests, long skippedRequests, int inFlightRequests,
        double observedRequestsPerSecond, Double estimatedPhaseRemainingMillis) { }
    public record LabEvent(String timestamp, String name, String type, String from, String to, String reason) { }
    public record LabStatus(String scenario, String stage, int successRatio, boolean running,
        int requestedRatePerCircuit, long dispatchedRequests, long completedRequests, long transportErrors,
        long skippedRequests, List<CircuitStatus> circuits, List<LabEvent> events, RecoverySettings configuration) { }
    public record RecoverySettings(@JsonUnwrapped LabSettings settings, long revision, long trialRevision,
        String instanceId, long trialStartedAtEpochMillis) {
        public int trafficPercentageStep() { return settings.trafficPercentageStep(); }
        public long minimumPhaseDurationMillis() { return settings.minimumPhaseDurationMillis(); }
        public int minimumNumberOfCalls() { return settings.minimumNumberOfCalls(); }
        public String failureStrategy() { return settings.failureStrategy(); }
    }
    private record Run(CircuitBreaker smart, CircuitBreaker standard, LabSettings settings, long revision,
        long trialRevision, long trialStartedAtEpochMillis) { }
    private static final class LoadCounters {
        final AtomicLong dispatched = new AtomicLong(), completed = new AtomicLong(), skipped = new AtomicLong(), transportErrors = new AtomicLong();
        final AtomicInteger inFlight = new AtomicInteger();
        volatile long phaseStartedNanos;
        volatile double observedRate;
        long sampledAt, sampledDispatched;
        void measure(long now) {
            if (sampledAt == 0) { sampledAt = now; sampledDispatched = dispatched.get(); return; }
            if (now - sampledAt >= 1_000_000_000L) {
                long total = dispatched.get(); observedRate = (total - sampledDispatched) * 1_000_000_000d / (now - sampledAt);
                sampledAt = now; sampledDispatched = total;
            }
        }
    }
    public static final class ConfigurationBusyException extends RuntimeException {
        ConfigurationBusyException() { super("The demo is still draining requests or applying another configuration. Please try again."); }
    }
}
