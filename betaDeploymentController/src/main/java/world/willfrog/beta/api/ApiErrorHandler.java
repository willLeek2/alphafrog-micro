package world.willfrog.beta.api;

import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import world.willfrog.beta.core.ControllerException;

@RestControllerAdvice
public class ApiErrorHandler {
    @ExceptionHandler(ControllerException.class)
    public ResponseEntity<Map<String, String>> controller(ControllerException failure) {
        return ResponseEntity.status(HttpStatus.CONFLICT)
                .body(Map.of("code", failure.code(), "message", safe(failure.getMessage())));
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<Map<String, String>> invalid(IllegalArgumentException failure) {
        return ResponseEntity.badRequest().body(Map.of("code", "REQUEST_INVALID", "message", safe(failure.getMessage())));
    }

    private String safe(String message) {
        if (message == null || message.isBlank()) return "Request failed";
        String clean = message.replaceAll("[\\p{Cntrl}]", " ").strip();
        return clean.substring(0, Math.min(clean.length(), 1024));
    }
}
