package dev.feed;

import java.util.Map;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;

@RestControllerAdvice
class Errors {
    @ExceptionHandler(DataIntegrityViolationException.class)
    ResponseEntity<?> conflict() {
        return ResponseEntity.status(409).body(Map.of("message", "Email already registered or invalid reference"));
    }

    @ExceptionHandler(RedisConnectionFailureException.class)
    ResponseEntity<?> unavailable() {
        return ResponseEntity.status(503).body(Map.of("message", "Feed storage temporarily unavailable; retry shortly"));
    }
}
