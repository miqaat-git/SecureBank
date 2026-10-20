package com.securebank.account.exception;

import org.springframework.http.HttpStatus;

/** Machine-readable error codes returned in {@link ApiError#code()} together with their HTTP status. */
public enum ErrorCode {

    VALIDATION_FAILED(HttpStatus.BAD_REQUEST),
    UNSUPPORTED_CURRENCY(HttpStatus.BAD_REQUEST),
    INVALID_AMOUNT(HttpStatus.BAD_REQUEST),

    AUTHENTICATION_REQUIRED(HttpStatus.UNAUTHORIZED),
    ACCESS_DENIED(HttpStatus.FORBIDDEN),

    ACCOUNT_NOT_FOUND(HttpStatus.NOT_FOUND),

    CUSTOMER_NOT_FOUND(HttpStatus.UNPROCESSABLE_ENTITY),
    CUSTOMER_NOT_ELIGIBLE(HttpStatus.UNPROCESSABLE_ENTITY),
    INSUFFICIENT_FUNDS(HttpStatus.UNPROCESSABLE_ENTITY),
    BALANCE_LIMIT_EXCEEDED(HttpStatus.UNPROCESSABLE_ENTITY),

    ACCOUNT_LIMIT_EXCEEDED(HttpStatus.CONFLICT),
    INVALID_ACCOUNT_STATE(HttpStatus.CONFLICT),
    ACCOUNT_NOT_OPERATIONAL(HttpStatus.CONFLICT),
    ACCOUNT_CLOSURE_NOT_ALLOWED(HttpStatus.CONFLICT),
    IDEMPOTENCY_KEY_REUSED(HttpStatus.CONFLICT),
    CONCURRENT_MODIFICATION(HttpStatus.CONFLICT),
    DATA_CONFLICT(HttpStatus.CONFLICT),

    CUSTOMER_SERVICE_UNAVAILABLE(HttpStatus.SERVICE_UNAVAILABLE),
    ACCOUNT_NUMBER_UNAVAILABLE(HttpStatus.SERVICE_UNAVAILABLE),

    INTERNAL_ERROR(HttpStatus.INTERNAL_SERVER_ERROR);

    private final HttpStatus status;

    ErrorCode(HttpStatus status) {
        this.status = status;
    }

    public HttpStatus status() {
        return status;
    }
}
