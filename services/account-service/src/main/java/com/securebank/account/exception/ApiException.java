package com.securebank.account.exception;

/**
 * Business or integration failure that maps to a well-defined HTTP response. The message must be safe to show
 * to API clients (no SQL, stack traces, secrets or other customers' data).
 */
public class ApiException extends RuntimeException {

    private final ErrorCode errorCode;

    public ApiException(ErrorCode errorCode, String message) {
        super(message);
        this.errorCode = errorCode;
    }

    public ApiException(ErrorCode errorCode, String message, Throwable cause) {
        super(message, cause);
        this.errorCode = errorCode;
    }

    public ErrorCode getErrorCode() {
        return errorCode;
    }
}
