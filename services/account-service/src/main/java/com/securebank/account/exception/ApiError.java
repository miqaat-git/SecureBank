package com.securebank.account.exception;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.time.Instant;
import java.util.List;
import org.springframework.http.HttpStatus;

/** The single error body format used by every error response of this service. */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ApiError(
        Instant timestamp,
        int status,
        String error,
        String code,
        String message,
        String path,
        List<FieldViolation> fieldErrors) {

    /** One invalid request field. */
    public record FieldViolation(String field, String message) {
    }

    public static ApiError of(Instant now, HttpStatus status, String code, String message, String path) {
        return new ApiError(now, status.value(), status.getReasonPhrase(), code, message, path, null);
    }

    public static ApiError of(Instant now, HttpStatus status, String code, String message, String path,
                              List<FieldViolation> fieldErrors) {
        return new ApiError(now, status.value(), status.getReasonPhrase(), code, message, path, fieldErrors);
    }
}
