package ru.itmo.nemat.tgconnector.miniapp;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.time.Instant;

@RestControllerAdvice(assignableTypes = MiniAppController.class)
public class MiniAppExceptionHandler {

    @ExceptionHandler(MiniAppAuthenticationException.class)
    public ResponseEntity<ApiError> unauthorized(
            MiniAppAuthenticationException exception
    ) {
        return response(HttpStatus.UNAUTHORIZED, exception.getMessage());
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<ApiError> badRequest(
            IllegalArgumentException exception
    ) {
        return response(HttpStatus.BAD_REQUEST, exception.getMessage());
    }

    @ExceptionHandler(IllegalStateException.class)
    public ResponseEntity<ApiError> conflict(
            IllegalStateException exception
    ) {
        return response(HttpStatus.CONFLICT, exception.getMessage());
    }

    private ResponseEntity<ApiError> response(
            HttpStatus status,
            String message
    ) {
        return ResponseEntity.status(status).body(new ApiError(
                status.value(),
                status.getReasonPhrase(),
                message,
                Instant.now()
        ));
    }

    public record ApiError(
            int status,
            String error,
            String message,
            Instant timestamp
    ) {
    }
}
