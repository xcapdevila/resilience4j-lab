package io.capdevila.poc.sr4j;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

@ControllerAdvice
public class DemoControllerAdvice {

    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public ResponseEntity<String> handleInvalidSuccessRatio(MethodArgumentTypeMismatchException e) {
        return ResponseEntity.badRequest().body(e.getName().equals("successRatio")
            ? DemoService.INVALID_SUCCESS_RATIO : e.getName() + " must be an integer in the supported range");
    }

    @ExceptionHandler(RuntimeException.class)
    public ResponseEntity<String> handleRuntimeException(RuntimeException e) {
        String response = String.format("RuntimeException: [%s]", e.getMessage());
        return ResponseEntity.status(500).body(response);
    }
}
