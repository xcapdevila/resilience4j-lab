package io.capdevila.poc.sr4j.lab;

import com.fasterxml.jackson.databind.JsonNode;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.ClassPathResource;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.context.annotation.Profile;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

@RestController
@Profile("lab")
@RequestMapping("/lab")
public class LabController {
    private final LabTrafficService traffic;
    private final String grafanaUrl;
    private final String prometheusUrl;
    private final Path dashboardFile;

    public LabController(LabTrafficService traffic,
        @Value("${lab.grafana-url:http://localhost:3000/d/r4j-local-lab}") String grafanaUrl,
        @Value("${lab.prometheus-url:http://localhost:9090}") String prometheusUrl,
        @Value("${lab.dashboard-file:/opt/lab/import-dashboard.json}") String dashboardFile) {
        this.traffic = traffic;
        this.grafanaUrl = grafanaUrl;
        this.prometheusUrl = prometheusUrl;
        this.dashboardFile = Path.of(dashboardFile);
    }

    @GetMapping(value = {"", "/"}, produces = MediaType.TEXT_HTML_VALUE)
    public String page() throws IOException {
        String html = new ClassPathResource("lab/index.html").getContentAsString(StandardCharsets.UTF_8);
        return html.replace("__GRAFANA_URL__", escape(grafanaUrl))
            .replace("__CLASSIC_GRAFANA_URL__", escape(grafanaUrl.replaceFirst("/d/.*$", "/d/r4j-local-classic")))
            .replace("__PROMETHEUS_URL__", escape(prometheusUrl));
    }

    @GetMapping("/api/status")
    public LabTrafficService.LabStatus status() { return traffic.status(); }

    @PostMapping("/api/scenario/{name}")
    public ResponseEntity<?> scenario(@PathVariable String name) {
        try {
            return ResponseEntity.ok(traffic.select(name));
        } catch (IllegalArgumentException error) {
            return ResponseEntity.badRequest().body(java.util.Map.of("error", error.getMessage()));
        }
    }

    @PostMapping("/api/configuration")
    public ResponseEntity<?> configure(@RequestBody JsonNode request) {
        try {
            return ResponseEntity.ok(traffic.configure(request));
        } catch (IllegalArgumentException error) {
            return ResponseEntity.badRequest().body(java.util.Map.of("error", error.getMessage()));
        } catch (LabTrafficService.ConfigurationBusyException error) {
            return ResponseEntity.status(503).body(java.util.Map.of("error", error.getMessage()));
        }
    }

    @GetMapping(value = "/dashboard", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<byte[]> dashboard() throws IOException {
        if (!Files.isRegularFile(dashboardFile)) return ResponseEntity.notFound().build();
        return ResponseEntity.ok().header(HttpHeaders.CONTENT_DISPOSITION,
            ContentDisposition.attachment().filename("grafana_recovery_dashboard.json").build().toString())
            .body(Files.readAllBytes(dashboardFile));
    }

    private static String escape(String value) {
        return value.replace("&", "&amp;").replace("\"", "&quot;").replace("<", "&lt;").replace(">", "&gt;");
    }
}
