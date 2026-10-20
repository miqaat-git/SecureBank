package com.securebank.transaction.client;

/**
 * Classified outcome of a remote call. Raw remote error text is never carried: the code is either one of
 * ours or a sanitized token taken from the remote error body.
 *
 * <ul>
 *   <li>SUCCEEDED - the remote service definitively applied the operation.</li>
 *   <li>REJECTED - definitive business/validation rejection; the operation was NOT applied.</li>
 *   <li>NOT_FOUND - (status lookups only) the remote service has no record of the operation.</li>
 *   <li>RETRYABLE_FAILURE - provably NOT applied (request refused or never sent); safe to retry.</li>
 *   <li>UNKNOWN - ambiguous: the remote side may or may not have applied it.</li>
 * </ul>
 */
public record RemoteResult(Outcome outcome, String code) {

    public enum Outcome { SUCCEEDED, REJECTED, NOT_FOUND, RETRYABLE_FAILURE, UNKNOWN }

    public static RemoteResult succeeded() { return new RemoteResult(Outcome.SUCCEEDED, "OK"); }
    public static RemoteResult rejected(String code) { return new RemoteResult(Outcome.REJECTED, code); }
    public static RemoteResult notFound() { return new RemoteResult(Outcome.NOT_FOUND, "NOT_FOUND"); }
    public static RemoteResult retryable(String code) { return new RemoteResult(Outcome.RETRYABLE_FAILURE, code); }
    public static RemoteResult unknown(String code) { return new RemoteResult(Outcome.UNKNOWN, code); }
}
