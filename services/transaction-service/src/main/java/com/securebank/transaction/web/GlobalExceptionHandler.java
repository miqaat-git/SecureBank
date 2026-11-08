package com.securebank.transaction.web;

import com.securebank.transaction.domain.IllegalStateTransitionException;
import com.securebank.transaction.exception.ApiException;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingRequestHeaderException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

/** Maps every failure to the consistent {@link ApiError} body. Internal details are logged, never returned. */
@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    @ExceptionHandler(ApiException.class)
    public ResponseEntity<ApiError> handleApi(ApiException e) {
        return build(e.getStatus(), e.getCode(), e.getMessage(), List.of());
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ApiError> handleValidation(MethodArgumentNotValidException e) {
        List<ApiError.FieldIssue> issues = e.getBindingResult().getFieldErrors().stream()
                .map(f -> new ApiError.FieldIssue(f.getField(), f.getDefaultMessage())).toList();
        return build(HttpStatus.BAD_REQUEST, "VALIDATION_FAILED", "Request validation failed.", issues);
    }

    @ExceptionHandler({HttpMessageNotReadableException.class, MethodArgumentTypeMismatchException.class,
            MissingRequestHeaderException.class, MissingServletRequestParameterException.class})
    public ResponseEntity<ApiError> handleMalformed(Exception e) {
        return build(HttpStatus.BAD_REQUEST, "MALFORMED_REQUEST", "The request is malformed or incomplete.", List.of());
    }

    @ExceptionHandler(HttpMediaTypeNotSupportedException.class)
    public ResponseEntity<ApiError> handleMediaType(HttpMediaTypeNotSupportedException e) {
        return build(HttpStatus.UNSUPPORTED_MEDIA_TYPE, "UNSUPPORTED_MEDIA_TYPE", "Use application/json.", List.of());
    }

    @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
    public ResponseEntity<ApiError> handleMethod(HttpRequestMethodNotSupportedException e) {
        return build(HttpStatus.METHOD_NOT_ALLOWED, "METHOD_NOT_ALLOWED", "Method not allowed.", List.of());
    }

    @ExceptionHandler(NoResourceFoundException.class)
    public ResponseEntity<ApiError> handleNoResource(NoResourceFoundException e) {
        return build(HttpStatus.NOT_FOUND, "NOT_FOUND", "Resource not found.", List.of());
    }

    @ExceptionHandler(AccessDeniedException.class)
    public ResponseEntity<ApiError> handleDenied(AccessDeniedException e) {
        return build(HttpStatus.FORBIDDEN, "ACCESS_DENIED", "You do not have permission for this operation.", List.of());
    }

    @ExceptionHandler(IllegalStateTransitionException.class)
    public ResponseEntity<ApiError> handleTransition(IllegalStateTransitionException e) {
        log.warn("Rejected illegal transition {} -> {}", e.getFrom(), e.getTo());
        return build(HttpStatus.CONFLICT, "INVALID_STATE", "The transfer is not in a state that allows this.", List.of());
    }

    @ExceptionHandler(ObjectOptimisticLockingFailureException.class)
    public ResponseEntity<ApiError> handleLocking(ObjectOptimisticLockingFailureException e) {
        return build(HttpStatus.CONFLICT, "CONCURRENT_MODIFICATION", "The transfer was modified concurrently; retry.",
                List.of());
    }

    @ExceptionHandler(DataIntegrityViolationException.class)
    public ResponseEntity<ApiError> handleIntegrity(DataIntegrityViolationException e) {
        log.warn("Data integrity violation: {}", e.getClass().getSimpleName());
        return build(HttpStatus.CONFLICT, "CONFLICT", "The request conflicts with existing data.", List.of());
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiError> handleUnexpected(Exception e) {
        log.error("Unexpected error", e);   // full detail stays in the server log only
        return build(HttpStatus.INTERNAL_SERVER_ERROR, "INTERNAL_ERROR", "An unexpected error occurred.", List.of());
    }

    private static ResponseEntity<ApiError> build(HttpStatus status, String code, String message,
                                                  List<ApiError.FieldIssue> details) {
        return ResponseEntity.status(status).body(new ApiError(java.time.Instant.now(), status.value(), code,
                message, details));
    }
}
