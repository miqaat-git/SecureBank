package com.securebank.customer.dto;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.time.Instant;
import java.util.List;

/** The one error shape returned by every failing endpoint. Never contains SQL, stack traces or internals. */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ErrorResponse(
        Instant timestamp,
        int status,
        String error,
        String message,
        String path,
        List<FieldViolation> fieldErrors) {

    /** One invalid request field. */
    public record FieldViolation(String field, String message) {
    }
}
