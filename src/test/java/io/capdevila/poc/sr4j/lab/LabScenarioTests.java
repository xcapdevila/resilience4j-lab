package io.capdevila.poc.sr4j.lab;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class LabScenarioTests {
    private final LabScenario scenario = new LabScenario();

    @Test
    void cycleProducesHealthyFailureAndFullRecoveryWindowsThenRepeats() {
        scenario.select("cycle", 0);
        assertThat(scenario.tick(7_999, 0, "CLOSED", 25).successRatio()).isEqualTo(100);
        assertThat(scenario.tick(8_000, 0, "CLOSED", 25).successRatio()).isZero();
        assertThat(scenario.tick(15_999, 0, "OPEN", 25).stage()).isEqualTo("outage");
        assertThat(scenario.tick(16_000, 25, "HALF_OPEN", 25).stage()).isEqualTo("recovery");
        assertThat(scenario.tick(70_999, 100, "CLOSED", 25).stage()).isEqualTo("recovery");
        assertThat(scenario.tick(71_000, 0, "CLOSED", 25).stage()).isEqualTo("healthy");
        assertThat(scenario.tick(79_000, 0, "CLOSED", 25).successRatio()).isZero();
    }

    @Test
    void stepBackWaitsForActualTargetThenStopsFailuresAfterActualRegression() {
        scenario.select("step-back", 0);
        assertThat(scenario.tick(0, 0, "CLOSED", 25).successRatio()).isZero();
        assertThat(scenario.tick(8_000, 25, "HALF_OPEN", 25).successRatio()).isEqualTo(100);
        assertThat(scenario.tick(50_000, 25, "HALF_OPEN", 25).successRatio()).isEqualTo(100);
        assertThat(scenario.tick(50_001, 50, "HALF_OPEN", 25).stage()).isEqualTo("step-back-failure");
        assertThat(scenario.tick(50_002, 50, "HALF_OPEN", 25).successRatio()).isZero();
        assertThat(scenario.tick(50_003, 25, "HALF_OPEN", 25).stage()).isEqualTo("recovery-after-step-back");
        assertThat(scenario.tick(50_004, 25, "HALF_OPEN", 25).successRatio()).isEqualTo(100);
        assertThat(scenario.tick(100_000, 50, "HALF_OPEN", 25).successRatio()).isEqualTo(100);
    }

    @Test
    void aLongConfiguredRecoveryIsNotInterruptedByTheAutomaticCycle() {
        scenario.select("cycle", 0);
        scenario.tick(8_000, 0, "CLOSED", 25);
        scenario.tick(16_000, 25, "HALF_OPEN", 25);
        assertThat(scenario.tick(71_000, 50, "HALF_OPEN", 25).stage()).isEqualTo("recovery");
        assertThat(scenario.tick(3_600_000, 100, "HALF_OPEN", 25).successRatio()).isEqualTo(100);
        assertThat(scenario.tick(3_600_001, 0, "CLOSED", 25).stage()).isEqualTo("healthy");
    }

    @Test
    void aSmallStepCanExerciseStepBackBeforeTheOldThirtyPercentThreshold() {
        scenario.select("step-back", 0);
        scenario.tick(8_000, 5, "HALF_OPEN", 5);
        assertThat(scenario.tick(8_001, 5, "HALF_OPEN", 5).successRatio()).isEqualTo(100);
        assertThat(scenario.tick(8_002, 10, "HALF_OPEN", 5).successRatio()).isZero();
        assertThat(scenario.tick(8_003, 5, "HALF_OPEN", 5).stage()).isEqualTo("recovery-after-step-back");
    }

    @Test
    void aSingleHundredPercentPhaseStopsItsFailurePulseWhenTheCircuitReopens() {
        scenario.select("step-back", 0);
        assertThat(scenario.tick(8_000, 100, "HALF_OPEN", 100).successRatio()).isEqualTo(100);
        assertThat(scenario.tick(8_001, 100, "HALF_OPEN", 100).stage()).isEqualTo("step-back-failure");
        assertThat(scenario.tick(8_002, 0, "OPEN", 100).stage()).isEqualTo("recovery-after-step-back");
        assertThat(scenario.tick(8_003, 100, "HALF_OPEN", 100).successRatio()).isEqualTo(100);
    }

    @Test
    void manualModesStaySelectedAndInvalidInputPreservesCurrentMode() {
        scenario.select("outage", 10);
        assertThat(scenario.tick(1_000_000, 0, "OPEN", 25).successRatio()).isZero();
        scenario.select("healthy", 20);
        assertThat(scenario.tick(1_000_000, 0, "OPEN", 25).successRatio()).isEqualTo(100);
        assertThatThrownBy(() -> scenario.select("typo", 30)).isInstanceOf(IllegalArgumentException.class);
        assertThat(scenario.snapshot().scenario()).isEqualTo("healthy");
        scenario.select("pause", 40);
        assertThat(scenario.snapshot().running()).isFalse();
    }
}
