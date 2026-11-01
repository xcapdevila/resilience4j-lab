package io.capdevila.poc.sr4j.lab;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import static org.hamcrest.Matchers.containsString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class LabControllerTests {
    @TempDir Path directory;

    @Test
    void servesControlsAndDownloadsOnlyTheConfiguredImportFile() throws Exception {
        Path dashboard = directory.resolve("dashboard.json");
        Files.writeString(dashboard, "{\"uid\":\"r4j-gradual-recovery\"}");
        MockMvc mvc = mvc(mock(LabTrafficService.class), dashboard);
        mvc.perform(get("/lab/")).andExpect(status().isOk())
            .andExpect(content().string(containsString("http://localhost:3000/d/r4j-local-lab")))
            .andExpect(content().string(containsString("Errors during recovery")))
            .andExpect(content().string(containsString("Failure strategy")))
            .andExpect(content().string(containsString("Minimum samples per phase")))
            .andExpect(content().string(containsString("<html lang=\"en\">")))
            .andExpect(content().string(containsString("value=\"OPEN\"")))
            .andExpect(content().string(containsString("value=\"STEP_BACK\"")));
        mvc.perform(get("/lab/dashboard")).andExpect(status().isOk())
            .andExpect(header().string("Content-Disposition", containsString("attachment")))
            .andExpect(content().json("{\"uid\":\"r4j-gradual-recovery\"}"));
        mvc.perform(get("/lab/dashboard/../../pom.xml")).andExpect(status().isNotFound());
    }

    @Test
    void rejectsUnknownScenarioAndDoesNotAcceptStateChangesViaGet() throws Exception {
        LabTrafficService traffic = mock(LabTrafficService.class);
        when(traffic.select("invalid")).thenThrow(new IllegalArgumentException("Unknown scenario: invalid"));
        MockMvc mvc = mvc(traffic, directory.resolve("missing.json"));
        mvc.perform(post("/lab/api/scenario/invalid")).andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.error").value("Unknown scenario: invalid"));
        mvc.perform(get("/lab/api/scenario/outage")).andExpect(status().isMethodNotAllowed());
        mvc.perform(post("/lab/api/scenario/outage")).andExpect(status().isOk());
        verify(traffic).select("outage");
        mvc.perform(get("/lab/dashboard")).andExpect(status().isNotFound());
    }

    private MockMvc mvc(LabTrafficService traffic, Path dashboard) {
        return MockMvcBuilders.standaloneSetup(new LabController(traffic,
            "http://localhost:3000/d/r4j-local-lab", "http://localhost:9090", dashboard.toString())).build();
    }
}
