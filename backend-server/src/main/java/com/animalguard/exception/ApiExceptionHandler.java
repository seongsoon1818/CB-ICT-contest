package com.animalguard.exception;

import com.fasterxml.jackson.annotation.JsonInclude;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.validation.FieldError;
import org.springframework.validation.ObjectError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.Comparator;
import java.util.List;

@RestControllerAdvice
public class ApiExceptionHandler {

    @ExceptionHandler(DuplicateDetectionEventException.class)
    public ResponseEntity<ApiError> handleDuplicate(DuplicateDetectionEventException exception) {
        return ResponseEntity.status(HttpStatus.CONFLICT)
                .body(new ApiError("DUPLICATE_EVENT", exception.getMessage()));
    }

    @ExceptionHandler(OperatorAuthenticationException.class)
    public ResponseEntity<ApiError> handleOperatorAuthentication(OperatorAuthenticationException exception) {
        return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                .body(new ApiError("OPERATOR_AUTHENTICATION_FAILED", exception.getMessage()));
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ApiError> handleValidation(MethodArgumentNotValidException exception) {
        List<ValidationViolation> violations = exception.getBindingResult().getAllErrors().stream()
                .map(this::toViolation)
                .sorted(Comparator.comparing(ValidationViolation::field)
                        .thenComparing(ValidationViolation::message))
                .toList();

        return ResponseEntity.badRequest()
                .body(new ApiError("VALIDATION_ERROR", "Request validation failed", violations));
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<ApiError> handleUnreadable(HttpMessageNotReadableException exception) {
        return ResponseEntity.badRequest()
                .body(new ApiError("INVALID_JSON", "Request body is invalid"));
    }

    private ValidationViolation toViolation(ObjectError error) {
        String field = error instanceof FieldError fieldError
                ? fieldError.getField()
                : error.getObjectName();
        String message = error.getDefaultMessage() == null ? "Invalid value" : error.getDefaultMessage();
        return new ValidationViolation(field, message);
    }

    @JsonInclude(JsonInclude.Include.NON_EMPTY)
    public record ApiError(String code, String message, List<ValidationViolation> violations) {

        public ApiError(String code, String message) {
            this(code, message, List.of());
        }
    }

    public record ValidationViolation(String field, String message) {
    }
}
