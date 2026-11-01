package io.capdevila.poc.sr4j.lab;

/** Monotonic fractional pacing, without a request queue or post-pause catch-up bursts. */
final class TrafficPacer {
    private long previousNanos;
    private int previousRate = -1;
    private double credit;
    private boolean active;

    int poll(long nowNanos, int requestsPerSecond, boolean running) {
        if (requestsPerSecond < 0 || requestsPerSecond > 1_000) throw new IllegalArgumentException("Invalid request rate");
        if (!running || requestsPerSecond == 0) { reset(); return 0; }
        if (!active || previousRate != requestsPerSecond || nowNanos < previousNanos) {
            active = true; previousNanos = nowNanos; previousRate = requestsPerSecond; credit = 0; return 0;
        }
        long elapsed = nowNanos - previousNanos;
        previousNanos = nowNanos;
        long maxElapsed = Math.max(50_000_000L, 1_000_000_000L / requestsPerSecond);
        credit += Math.min(elapsed, maxElapsed) * requestsPerSecond / 1_000_000_000d;
        int whole = (int) Math.floor(credit + 1e-9);
        credit -= whole;
        return Math.min(whole, Math.max(1, (requestsPerSecond + 99) / 100));
    }

    void reset() { active = false; credit = 0; previousRate = -1; }
}
