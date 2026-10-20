package com.securebank.account.exception;

import jakarta.servlet.http.HttpServletRequest;
import java.time.Clock;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.ConcurrencyFailureException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ResponseEntity;
import org.springframework.lang.Nullable;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.ServletWebRequest;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

/**
 * Centralised exception handling. Every response uses the {@link ApiError} format. Messages are fixed, safe texts:
 * stack traces, SQL and exception messages from frameworks are logged server-side and never returned.
 */
@RestControllerAdvice
public class GlobalExceptionHandler extends ResponseEntityExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    private final Clock clock;

    public GlobalExceptionHandler(Clock clock) {
        this.clock = clock;
    }

    @ExceptionHandler(ApiException.class)
    public ResponseEntity<ApiError> handleApiException(ApiException ex, HttpServletRequest request) {
        ErrorCode code = ex.getErrorCode();
        if (code.status().is5xxServerError()) {
            log.warn("Request failed with {}: {}", code, ex.getMessage(), ex.getCause());
        } else {
            log.debug("Request rejected with {}: {}", code, ex.getMessage());
        }
        return respond(code.status(), code.name(), ex.getMessage(), request.getRequestURI(), null);
    }

    @ExceptionHandler(AccessDeniedException.class)
    public ResponseEntity<ApiError> handleAccessDenied(AccessDeniedException ex, HttpServletRequest request) {
        return respond(HttpStatus.FORBIDDEN, ErrorCode.ACCESS_DENIED.name(),
                "You do not have permission to perform this operation.", request.getRequestURI(), null);
    }

    @ExceptionHandler(ConcurrencyFailureException.class)
    public ResponseEntity<ApiError> handleOptimisticLock(ConcurrencyFailureException ex,
                                                         HttpServletRequest request) {
        log.info("Optimistic locking conflict on {}", request.getRequestURI());
        return respond(HttpStatus.CONFLICT, ErrorCode.CONCURRENT_MODIFICATION.name(),
                "The account was modified concurrently. Reload it and retry the operation.",
                request.getRequestURI(), null);
    }

    @ExceptionHandler(DataIntegrityViolationException.class)
    public ResponseEntity<ApiError> handleDataIntegrity(DataIntegrityViolationException ex,
                                                        HttpServletRequest request) {
        log.warn("Data integrity violation on {}", request.getRequestURI(), ex);
        return respond(HttpStatus.CONFLICT, ErrorCode.DATA_CONFLICT.name(),
                "The request conflicts with the current state of the resource.", request.getRequestURI(), null);
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiError> handleUnexpected(Exception ex, HttpServletRequest request) {
        log.error("Unhandled exception on {}", request.getRequestURI(), ex);
        return respond(HttpStatus.INTERNAL_SERVER_ERROR, ErrorCode.INTERNAL_ERROR.name(),
                "An unexpected error occurred.", request.getRequestURI(), null);
    }

    @Override
    protected ResponseEntity<Object> handleMethodArgumentNotValid(MethodArgumentNotValidException ex,
                                                                  HttpHeaders headers, HttpStatusCode status,
                                                                  WebRequest request) {
        List<ApiError.FieldViolation> violations = ex.getBindingResult().getFieldErrors().stream()
                .map(fe -> new ApiError.FieldViolation(fe.getField(), fe.getDefaultMessage()))
                .toList();
        ApiError body = ApiError.of(clock.instant(), HttpStatus.BAD_REQUEST, ErrorCode.VALIDATION_FAILED.name(),
                "The request contains invalid fields.", path(request), violations);
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).headers(headers).body(body);
    }

    /** All remaining Spring MVC exceptions (bad JSON, bad UUID, wrong method, ...) end up here. */
    @Override
    protected ResponseEntity<Object> handleExceptionInternal(Exception ex, @Nullable Object body,
                                                             HttpHeaders headers, HttpStatusCode statusCode,
                                                             WebRequest request) {
        HttpStatus status = HttpStatus.resolve(statusCode.value());
        if (status == null) {
            status = HttpStatus.INTERNAL_SERVER_ERROR;
        }
        if (status.is5xxServerError()) {
            log.error("Server error while handling request", ex);
        }
        ApiError error = ApiError.of(clock.instant(), status, status.name(), safeMessage(status), path(request));
        return ResponseEntity.status(statusCode).headers(headers).body(error);
    }

    private ResponseEntity<ApiError> respond(HttpStatus status, String code, String message, String path,
                                             List<ApiError.FieldViolation> violations) {
        return ResponseEntity.status(status).body(ApiError.of(clock.instant(), status, code, message, path,
                violations));
    }

    private static String safeMessage(HttpStatus status) {
        return switch (status) {
            case BAD_REQUEST -> "The request is malformed or contains invalid values.";
            case NOT_FOUND -> "The requested resource was not found.";
            case METHOD_NOT_ALLOWED -> "This HTTP method is not supported for the requested resource.";
            case NOT_ACCEPTABLE -> "The requested response format is not supported.";
            case UNSUPPORTED_MEDIA_TYPE -> "The request content type is not supported.";
            default -> status.is5xxServerError()
                    ? "An unexpected error occurred."
                    : "The request could not be processed.";
        };
    }

    @Nullable
    private static String path(WebRequest request) {
        if (request instanceof ServletWebRequest servletRequest) {
            return servletRequest.getRequest().getRequestURI();
        }
        return null;
    }
}
