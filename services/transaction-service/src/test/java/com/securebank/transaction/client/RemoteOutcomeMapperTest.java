package com.securebank.transaction.client;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.securebank.transaction.client.RemoteResult.Outcome;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class RemoteOutcomeMapperTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final Set<String> SUCCESS = Set.of("SUCCEEDED");

    private static RemoteResult submit(int status, String body) {
        return RemoteOutcomeMapper.map(HttpResult.response(status, body), MAPPER, SUCCESS, false);
    }

    private static RemoteResult lookup(int status, String body) {
        return RemoteOutcomeMapper.map(HttpResult.response(status, body), MAPPER, SUCCESS, true);
    }

    @Test
    void accountConcurrentModificationConflictIsRetryableNotRejected() {
        RemoteResult r = submit(409, "{\"code\":\"CONCURRENT_MODIFICATION\"}");
        assertThat(r.outcome()).isEqualTo(Outcome.RETRYABLE_FAILURE);
        assertThat(r.code()).isEqualTo("CONCURRENT_MODIFICATION");
        // Any other 409 (e.g. an idempotency-key conflict or an already-reversed debit) stays a rejection.
        assertThat(submit(409, "{\"code\":\"OPERATION_ALREADY_REVERSED\"}").outcome()).isEqualTo(Outcome.REJECTED);
    }

    @Test
    void successBodyIsSucceeded() {
        assertThat(submit(200, "{\"status\":\"SUCCEEDED\"}").outcome()).isEqualTo(Outcome.SUCCEEDED);
        assertThat(submit(201, "{\"status\":\"succeeded\"}").outcome()).isEqualTo(Outcome.SUCCEEDED);
    }

    @Test
    void pendingOrUnparseableSuccessIsUnknownNeverSucceeded() {
        assertThat(submit(202, "{\"status\":\"PENDING\"}").outcome()).isEqualTo(Outcome.UNKNOWN);
        assertThat(submit(200, "not json").outcome()).isEqualTo(Outcome.UNKNOWN);
        assertThat(submit(200, "").outcome()).isEqualTo(Outcome.UNKNOWN);
        assertThat(submit(200, "{\"status\":\"MYSTERY\"}").outcome()).isEqualTo(Outcome.UNKNOWN);
    }

    @Test
    void rejectedBodyCarriesSanitizedCode() {
        RemoteResult r = submit(200, "{\"status\":\"REJECTED\",\"reasonCode\":\"INSUFFICIENT_FUNDS\"}");
        assertThat(r.outcome()).isEqualTo(Outcome.REJECTED);
        assertThat(r.code()).isEqualTo("INSUFFICIENT_FUNDS");
    }

    @Test
    void remoteTextThatIsNotAShoutyCodeIsDropped() {
        RemoteResult r = submit(422, "{\"code\":\"SQL error: select * from accounts\"}");
        assertThat(r.outcome()).isEqualTo(Outcome.REJECTED);
        assertThat(r.code()).isEqualTo("HTTP_422");
    }

    @ParameterizedTest
    @CsvSource({"400,REJECTED", "404,REJECTED", "422,REJECTED", "409,REJECTED",
            "401,RETRYABLE_FAILURE", "403,RETRYABLE_FAILURE", "429,RETRYABLE_FAILURE", "503,RETRYABLE_FAILURE",
            "408,UNKNOWN", "500,UNKNOWN", "502,UNKNOWN", "504,UNKNOWN"})
    void submissionStatusCodesAreClassifiedConservatively(int status, Outcome expected) {
        assertThat(submit(status, "{}").outcome()).isEqualTo(expected);
    }

    @Test
    void conflictWhileOperationInProgressIsUnknown() {
        assertThat(submit(409, "{\"code\":\"OPERATION_IN_PROGRESS\"}").outcome()).isEqualTo(Outcome.UNKNOWN);
    }

    @Test
    void transportFailuresDistinguishNeverSentFromAmbiguous() {
        assertThat(RemoteOutcomeMapper.map(HttpResult.notSent(), MAPPER, SUCCESS, false).outcome())
                .isEqualTo(Outcome.RETRYABLE_FAILURE);
        assertThat(RemoteOutcomeMapper.map(HttpResult.ambiguous(), MAPPER, SUCCESS, false).outcome())
                .isEqualTo(Outcome.UNKNOWN);
        assertThat(RemoteOutcomeMapper.map(HttpResult.notSent(), MAPPER, SUCCESS, true).outcome())
                .isEqualTo(Outcome.UNKNOWN);
    }

    @Test
    void lookupsMapNotFoundAndNeverClaimRetryable() {
        assertThat(lookup(404, "").outcome()).isEqualTo(Outcome.NOT_FOUND);
        assertThat(lookup(500, "").outcome()).isEqualTo(Outcome.UNKNOWN);
        assertThat(lookup(503, "").outcome()).isEqualTo(Outcome.UNKNOWN);
        assertThat(lookup(200, "{\"status\":\"SUCCEEDED\"}").outcome()).isEqualTo(Outcome.SUCCEEDED);
    }
}
