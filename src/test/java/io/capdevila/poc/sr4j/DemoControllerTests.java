package io.capdevila.poc.sr4j;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class DemoControllerTests {

    private DemoService demoService;
    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        demoService = mock(DemoService.class);
        mvc = MockMvcBuilders.standaloneSetup(new DemoController(demoService))
            .setControllerAdvice(new DemoControllerAdvice())
            .build();
    }

    @ParameterizedTest
    @ValueSource(ints = {0, 50, 100})
    void passesValidPercentagesToTheService(int successRatio) throws Exception {
        when(demoService.process(successRatio)).thenReturn("OK");

        mvc.perform(get("/process/{successRatio}", successRatio))
            .andExpect(status().isOk())
            .andExpect(content().string("OK"));

        verify(demoService).process(successRatio);
    }

    @ParameterizedTest
    @ValueSource(ints = {0, 50, 100})
    void passesValidPercentagesToTheStandardService(int successRatio) throws Exception {
        when(demoService.processStandard(successRatio)).thenReturn("OK");

        mvc.perform(get("/process-standard/{successRatio}", successRatio))
            .andExpect(status().isOk())
            .andExpect(content().string("OK"));

        verify(demoService).processStandard(successRatio);
    }

    @ParameterizedTest
    @ValueSource(ints = {-1, 101, Integer.MIN_VALUE, Integer.MAX_VALUE})
    void rejectsPercentagesOutsideTheRangeBeforeCallingTheService(int successRatio) throws Exception {
        mvc.perform(get("/process/{successRatio}", successRatio))
            .andExpect(status().isBadRequest())
            .andExpect(content().string(DemoService.INVALID_SUCCESS_RATIO));
        mvc.perform(get("/process-standard/{successRatio}", successRatio))
            .andExpect(status().isBadRequest())
            .andExpect(content().string(DemoService.INVALID_SUCCESS_RATIO));

        verifyNoInteractions(demoService);
    }

    @ParameterizedTest
    @ValueSource(strings = {"abc", "1.5", "2147483648"})
    void rejectsNonIntegerInputBeforeCallingTheService(String successRatio) throws Exception {
        mvc.perform(get("/process/{successRatio}", successRatio))
            .andExpect(status().isBadRequest())
            .andExpect(content().string(DemoService.INVALID_SUCCESS_RATIO));
        mvc.perform(get("/process-standard/{successRatio}", successRatio))
            .andExpect(status().isBadRequest())
            .andExpect(content().string(DemoService.INVALID_SUCCESS_RATIO));

        verifyNoInteractions(demoService);
    }

    @Test
    void forwardsSimulationParametersToTheDecoratedOverloadsForBothPolicies() throws Exception {
        when(demoService.process(100, 20, 25, 40)).thenReturn("OK");
        when(demoService.processStandard(100, 20, 25, 40)).thenReturn("OK");
        for (String route : new String[] {"/process/100", "/process-standard/100"}) {
            mvc.perform(get(route).queryParam("latencyMillis", "20")
                .queryParam("slowCallPercentage", "25").queryParam("slowExtraLatencyMillis", "40"))
                .andExpect(status().isOk()).andExpect(content().string("OK"));
        }
        verify(demoService).process(100, 20, 25, 40);
        verify(demoService).processStandard(100, 20, 25, 40);
    }

    @ParameterizedTest
    @ValueSource(strings = {"latencyMillis=-1", "latencyMillis=5001", "latencyMillis=1.5",
        "latencyMillis=abc", "latencyMillis=2147483648", "slowCallPercentage=-1",
        "slowCallPercentage=101", "slowExtraLatencyMillis=-1", "slowExtraLatencyMillis=5001",
        "latencyMillis=3000&slowExtraLatencyMillis=3000"})
    void rejectsInvalidSimulationBeforePermissionChecksOrBackendExecution(String parameters) throws Exception {
        for (String route : new String[] {"/process/100", "/process-standard/100"}) {
            var request = get(route);
            for (String parameter : parameters.split("&")) {
                String[] pair = parameter.split("=", 2);
                request.queryParam(pair[0], pair[1]);
            }
            mvc.perform(request).andExpect(status().isBadRequest());
        }
        verifyNoInteractions(demoService);
    }
}
