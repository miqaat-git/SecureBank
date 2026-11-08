package com.securebank.transaction.client;

/** Raw outcome of one HTTP exchange before business classification. */
public record HttpResult(Kind kind, int status, String body) {

    public enum Kind {
        /** A complete HTTP response was received. */
        RESPONSE,
        /** The request provably never reached the server (connection refused, unknown host). */
        NOT_SENT,
        /** I/O failure after the request may have been sent (read timeout, reset, ...). Outcome unknown. */
        AMBIGUOUS_IO
    }

    public static HttpResult response(int status, String body) {
        return new HttpResult(Kind.RESPONSE, status, body);
    }

    public static HttpResult notSent() {
        return new HttpResult(Kind.NOT_SENT, 0, null);
    }

    public static HttpResult ambiguous() {
        return new HttpResult(Kind.AMBIGUOUS_IO, 0, null);
    }
}
