package io.capdevila.poc.sr4j.lab;

import java.util.Set;

/** Traffic instructions for the simulated backend; this never changes circuit state. */
final class LabScenario {
    private static final Set<String> NAMES = Set.of("cycle", "outage", "healthy", "step-back", "pause");
    private String scenario = "pause";
    private String stage = "paused";
    private long stageStarted;
    private int failureTarget;

    synchronized void select(String name, long now) {
        if (!NAMES.contains(name)) throw new IllegalArgumentException("Unknown scenario: " + name);
        scenario = name;
        stage = switch (name) {
            case "cycle", "healthy" -> "healthy";
            case "outage", "step-back" -> "outage";
            default -> "paused";
        };
        stageStarted = now;
        failureTarget = 0;
    }

    synchronized Instruction tick(long now, int smartTarget, String smartState, int firstTarget) {
        return tick(now, smartTarget, smartState, firstTarget, null);
    }

    synchronized Instruction tick(long now, int smartTarget, String smartState, int firstTarget, LabSettings settings) {
        long elapsed = now - stageStarted;
        long healthyDuration = settings == null ? 8_000 : settings.healthyDurationMillis();
        long outageDuration = settings == null ? 8_000 : settings.outageDurationMillis();
        long recoveryHold = settings == null ? 55_000 : settings.recoveryHoldDurationMillis();
        if (scenario.equals("cycle")) {
            if (stage.equals("healthy") && elapsed >= healthyDuration) advance("outage", now);
            else if (stage.equals("outage") && elapsed >= outageDuration) advance("recovery", now);
            else if (stage.equals("recovery") && elapsed >= recoveryHold && smartState.equals("CLOSED")) advance("healthy", now);
        } else if (scenario.equals("step-back")) {
            if (stage.equals("outage") && elapsed >= outageDuration) advance("recovery", now);
            else if (stage.equals("recovery") && smartState.equals("HALF_OPEN") &&
                (smartTarget > firstTarget || firstTarget == 100)) {
                failureTarget = smartTarget;
                advance("step-back-failure", now);
            } else if (stage.equals("step-back-failure") &&
                (smartTarget < failureTarget || !smartState.equals("HALF_OPEN"))) {
                advance("recovery-after-step-back", now);
            }
        }
        return snapshot(settings);
    }

    synchronized Instruction snapshot() { return snapshot(null); }

    synchronized Instruction snapshot(LabSettings settings) {
        int healthy = settings == null ? 100 : settings.backendSuccessRatio();
        int outage = settings == null ? 0 : settings.outageSuccessRatio();
        int recoveryFailure = settings == null ? 0 : settings.recoveryFailureSuccessRatio();
        int successRatio = stage.equals("outage") ? outage : stage.equals("step-back-failure") ? recoveryFailure : healthy;
        return new Instruction(scenario, stage, successRatio, !scenario.equals("pause"));
    }

    private void advance(String next, long now) {
        stage = next;
        stageStarted = now;
    }

    record Instruction(String scenario, String stage, int successRatio, boolean running) { }
}
