package com.securebank.transaction.client;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Maps raw HTTP outcomes to {@link RemoteResult}. Conservative by design:
 * anything that cannot be proven "not applied" or "applied" is UNKNOWN.
 */
public final class RemoteOutcomeMapper {

    private static final Set<String> REJECT_STATUSES = Set.of("REJECTED", "FAILED", "DECLINED");
    private static final Pattern SAFE_CODE = Pattern.compile("^[A-Z0-9_]{1,64}$");

    private RemoteOutcomeMapper() {
    }

    /**
     * @param successStatuses body "status" values that mean the operation is applied
     * @param lookup          true for status lookups (GET), false for submissions (POST)
     */
    public static RemoteResult map(HttpResult http, ObjectMapper mapper, Set<String> successStatuses,
                                   boolean lookup) {
        switch (http.kind()) {
            case NOT_SENT:
                return lookup ? RemoteResult.unknown("CONNECTION_FAILED") : RemoteResult.retryable("CONNECTION_FAILED");
            case AMBIGUOUS_IO:
                return RemoteResult.unknown("IO_ERROR_OR_TIMEOUT");
            default:
                break;
        }

        int s = http.status();
        JsonNode body = parse(http.body(), mapper);
        String status = text(body, "status");
        String code = sanitize(firstNonNull(text(body, "reasonCode"), text(body, "code")));

        if (s >= 200 && s < 300) {
            if (status == null) {
                return RemoteResult.unknown("UNPARSEABLE_RESPONSE");
            }
            String st = status.toUpperCase(Locale.ROOT);
            if (successStatuses.contains(st)) {
                return RemoteResult.succeeded();
            }
            if (REJECT_STATUSES.contains(st)) {
                return RemoteResult.rejected(code != null ? code : "REJECTED");
            }
            return RemoteResult.unknown("REMOTE_NOT_FINAL");
        }

        if (lookup) {
            return s == 404 ? RemoteResult.notFound() : RemoteResult.unknown("HTTP_" + s);
        }

        switch (s) {
            case 401:
            case 403:
                return RemoteResult.retryable("SERVICE_AUTH_FAILED");
            case 429:
            case 503:
                return RemoteResult.retryable("SERVICE_UNAVAILABLE");
            case 408:
            case 502:
            case 504:
                return RemoteResult.unknown("HTTP_" + s);
            case 409:
                if ("OPERATION_IN_PROGRESS".equals(code)) {
                    return RemoteResult.unknown("OPERATION_IN_PROGRESS");
                }
                if ("CONCURRENT_MODIFICATION".equals(code)) {
                    // Account Service rolled back its local transaction after exhausting optimistic-lock retries:
                    // the operation was provably not applied, and a retry with the same operation id is idempotent.
                    return RemoteResult.retryable("CONCURRENT_MODIFICATION");
                }
                return RemoteResult.rejected(code != null ? code : "CONFLICT");
            default:
                if (s >= 500) {
                    return RemoteResult.unknown("HTTP_" + s);
                }
                return RemoteResult.rejected(code != null ? code : "HTTP_" + s);
        }
    }

    private static JsonNode parse(String raw, ObjectMapper mapper) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            return mapper.readTree(raw);
        } catch (Exception e) {
            return null;
        }
    }

    private static String text(JsonNode node, String field) {
        if (node == null || !node.hasNonNull(field) || !node.get(field).isTextual()) {
            return null;
        }
        return node.get(field).asText();
    }

    private static String firstNonNull(String a, String b) {
        return a != null ? a : b;
    }

    /** Only codes shaped like SCREAMING_SNAKE_CASE are propagated; anything else is dropped. */
    private static String sanitize(String code) {
        if (code == null) {
            return null;
        }
        String c = code.trim().toUpperCase(Locale.ROOT);
        return SAFE_CODE.matcher(c).matches() ? c : null;
    }
}
