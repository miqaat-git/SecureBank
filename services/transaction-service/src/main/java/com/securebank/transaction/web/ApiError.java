package com.securebank.transaction.web;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.time.Instant;
import java.util.List;

/** Consistent error body. Never contains stack traces, SQL, credentials or remote error text. */
@JsonInclude(JsonInclude.Include.NON_EMPTY)
public record ApiError(Instant timestamp, int status, String code, String message, List<FieldIssue> details) {

    public record FieldIssue(String field, String message) {
    }

    public static ApiError of(int status, String code, String message) {
        return new ApiError(Instant.now(), status, code, message, List.of());
    }
}
