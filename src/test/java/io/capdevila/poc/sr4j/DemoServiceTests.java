package io.capdevila.poc.sr4j;

import java.util.Random;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

class DemoServiceTests {

    @ParameterizedTest
    @ValueSource(ints = {0, 1, 50, 99, 100})
    void percentageAcceptsExactlyThatManyOfTheHundredPossibleDraws(int successRatio) {
        int successes = 0;

        for (int draw = 0; draw < 100; draw++) {
            DemoService service = new DemoService(new FixedRandom(draw));
            try {
                assertThat(service.process(successRatio)).isEqualTo("OK");
                successes++;
            } catch (RuntimeException failure) {
                assertThat(failure).hasMessage(successRatio == 0 ? "Forced failure" : "Random failure");
            }
        }

        assertThat(successes).isEqualTo(successRatio);
    }

    @ParameterizedTest
    @ValueSource(ints = {-1, 101})
    void alsoRejectsOutOfRangeDirectServiceCalls(int successRatio) {
        DemoService service = new DemoService(new FixedRandom(0));

        assertThatIllegalArgumentException().isThrownBy(() -> service.process(successRatio))
            .withMessage(DemoService.INVALID_SUCCESS_RATIO);
    }

    private static final class FixedRandom extends Random {

        private final int draw;

        private FixedRandom(int draw) {
            this.draw = draw;
        }

        @Override
        public int nextInt(int bound) {
            assertThat(bound).isEqualTo(100);
            return draw;
        }
    }
}
