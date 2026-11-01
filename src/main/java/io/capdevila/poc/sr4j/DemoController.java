package io.capdevila.poc.sr4j;

import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;

import java.util.function.IntFunction;

@Controller
@RequiredArgsConstructor
public class DemoController {

    private final DemoService demoService;

    @GetMapping("/process/{successRatio}")
    public ResponseEntity<String> process(@PathVariable("successRatio") int successRatio,
        @RequestParam(defaultValue = "0") int latencyMillis,
        @RequestParam(defaultValue = "0") int slowCallPercentage,
        @RequestParam(defaultValue = "0") int slowExtraLatencyMillis) {
        if (!DemoService.validSimulation(latencyMillis, slowCallPercentage, slowExtraLatencyMillis)) {
            return ResponseEntity.badRequest().body(DemoService.INVALID_SIMULATION);
        }
        return process(successRatio, latencyMillis == 0 && slowCallPercentage == 0
            ? demoService::process : ratio -> demoService.process(ratio, latencyMillis, slowCallPercentage, slowExtraLatencyMillis));
    }

    @GetMapping("/process-standard/{successRatio}")
    public ResponseEntity<String> processStandard(@PathVariable("successRatio") int successRatio,
        @RequestParam(defaultValue = "0") int latencyMillis,
        @RequestParam(defaultValue = "0") int slowCallPercentage,
        @RequestParam(defaultValue = "0") int slowExtraLatencyMillis) {
        if (!DemoService.validSimulation(latencyMillis, slowCallPercentage, slowExtraLatencyMillis)) {
            return ResponseEntity.badRequest().body(DemoService.INVALID_SIMULATION);
        }
        return process(successRatio, latencyMillis == 0 && slowCallPercentage == 0
            ? demoService::processStandard : ratio -> demoService.processStandard(ratio, latencyMillis, slowCallPercentage, slowExtraLatencyMillis));
    }

    private ResponseEntity<String> process(int successRatio, IntFunction<String> backend) {
        if (successRatio < 0 || successRatio > 100) {
            return ResponseEntity.badRequest().body(DemoService.INVALID_SUCCESS_RATIO);
        }
        final String result = backend.apply(successRatio);
        if (result.equals("OK")) {
            return ResponseEntity.ok(result);
        }
        return ResponseEntity.status(500).body(result);
    }

}
