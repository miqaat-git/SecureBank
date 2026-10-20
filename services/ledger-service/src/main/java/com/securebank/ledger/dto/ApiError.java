package com.securebank.ledger.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.time.Instant;
import java.util.List;

/** The single error shape returned by every failing endpoint, including 401/403 from the security layer. */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ApiError(
        Instant timestamp,
        int status,
        String code,
        String message,
        String path,
        List<FieldViolation> violations) {

    public record FieldViolation(String field, String message) {
    }
}
