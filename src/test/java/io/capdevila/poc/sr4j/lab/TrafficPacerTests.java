package io.capdevila.poc.sr4j.lab;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TrafficPacerTests {
    @Test
    void fractionalRequestsAccumulateWithoutRoundingAwayLowRates() {
        for (int rate : new int[] {1, 3, 37, 100, 1_000}) {
            TrafficPacer pacer = new TrafficPacer();
            int dispatched = 0;
            for (int tick = 0; tick <= 1_000; tick++) {
                dispatched += pacer.poll(tick * 10_000_000L, rate, true);
            }
            assertThat(dispatched).as("ten seconds at %s requests/s", rate).isEqualTo(rate * 10);
        }
    }

    @Test
    void pausingAndZeroRateDiscardCreditInsteadOfQueuingRequests() {
        TrafficPacer pacer = new TrafficPacer();
        pacer.poll(0, 10, true);
        assertThat(pacer.poll(50_000_000L, 10, true)).isZero();
        assertThat(pacer.poll(60_000_000L, 10, false)).isZero();
        assertThat(pacer.poll(60_000_000_000L, 10, false)).isZero();
        assertThat(pacer.poll(60_010_000_000L, 10, true)).isZero();
        assertThat(pacer.poll(60_060_000_000L, 10, true)).isZero();
        assertThat(pacer.poll(60_110_000_000L, 10, true)).isEqualTo(1);
        assertThat(pacer.poll(61_000_000_000L, 0, true)).isZero();
        assertThat(pacer.poll(120_000_000_000L, 0, true)).isZero();
        assertThat(pacer.poll(120_010_000_000L, 10, true)).isZero();
    }

    @Test
    void changingRateDoesNotSpendCreditFromThePreviousRate() {
        TrafficPacer pacer = new TrafficPacer();
        for (int tick = 0; tick <= 40; tick++) {
            assertThat(pacer.poll(tick * 10_000_000L, 2, true)).isZero();
        }
        assertThat(pacer.poll(400_000_000L, 100, true)).isZero();
        assertThat(pacer.poll(410_000_000L, 100, true)).isEqualTo(1);
        assertThat(pacer.poll(410_000_000L, 100, true)).isZero();
    }

    @Test
    void schedulerStallsCannotCreateACatchUpBurstOrRepeatedBacklog() {
        TrafficPacer pacer = new TrafficPacer();
        pacer.poll(0, 1_000, true);
        assertThat(pacer.poll(60_000_000_000L, 1_000, true)).isBetween(1, 10);
        assertThat(pacer.poll(60_000_000_000L, 1_000, true)).isZero();
        assertThat(pacer.poll(60_010_000_000L, 1_000, true)).isEqualTo(10);
        assertThat(pacer.poll(1_000_000L, 1_000, true)).isZero();
        assertThatThrownBy(() -> pacer.poll(0, -1, true)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> pacer.poll(0, 1_001, true)).isInstanceOf(IllegalArgumentException.class);
    }
}
