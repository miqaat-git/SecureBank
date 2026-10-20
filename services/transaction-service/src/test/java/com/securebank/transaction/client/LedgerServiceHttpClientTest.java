package com.securebank.transaction.client;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.jsonPath;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withException;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.securebank.transaction.client.RemoteResult.Outcome;
import com.securebank.transaction.config.RemoteClientProperties;
import java.math.BigDecimal;
import java.net.SocketTimeoutException;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

class LedgerServiceHttpClientTest {

    private static final String BASE = "http://ledger.test";
    private static final UUID KEY = UUID.randomUUID();
    private static final String REF = "TRANSFER-" + UUID.randomUUID();

    private MockRestServiceServer server;
    private LedgerServiceHttpClient client;

    @BeforeEach
    void setUp() {
        RestClient.Builder builder = RestClient.builder().baseUrl(BASE);
        server = MockRestServiceServer.bindTo(builder).build();
        RemoteClientProperties.Ledger cfg = new RemoteClientProperties.Ledger(BASE, Duration.ofSeconds(1),
                Duration.ofSeconds(1), "/internal/journal-entries",
                "/internal/journal-entries/{businessReference}/reversals",
                "/internal/journal-entries/by-reference/{businessReference}");
        client = new LedgerServiceHttpClient(new RemoteHttp(builder.build(), Optional::empty), cfg,
                new ObjectMapper());
    }

    private LedgerPostRequest post() {
        UUID a = UUID.randomUUID();
        UUID b = UUID.randomUUID();
        return new LedgerPostRequest(REF, KEY, "desc", "INR", List.of(
                new LedgerEntryLine(a, "DEBIT", new BigDecimal("50.00")),
                new LedgerEntryLine(b, "CREDIT", new BigDecimal("50.00"))));
    }

    @Test
    void postSendsBalancedEntryWithBusinessReferenceAndIdempotencyKey() {
        server.expect(requestTo(BASE + "/internal/journal-entries"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(header("Idempotency-Key", KEY.toString()))
                .andExpect(jsonPath("$.businessReference").value(REF))
                .andExpect(jsonPath("$.entries.length()").value(2))
                .andExpect(jsonPath("$.entries[0].direction").value("DEBIT"))
                .andExpect(jsonPath("$.entries[1].direction").value("CREDIT"))
                .andRespond(withSuccess("{\"businessReference\":\"" + REF + "\",\"status\":\"POSTED\"}",
                        MediaType.APPLICATION_JSON));

        assertThat(client.post(post()).outcome()).isEqualTo(Outcome.SUCCEEDED);
        server.verify();
    }

    @Test
    void reversalUsesOriginalReferenceInPathNotBody() {
        server.expect(requestTo(BASE + "/internal/journal-entries/" + REF + "/reversals"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(jsonPath("$.reversalBusinessReference").value(REF + "-REVERSAL"))
                .andExpect(jsonPath("$.originalBusinessReference").doesNotExist())
                .andRespond(withSuccess("{\"status\":\"POSTED\"}", MediaType.APPLICATION_JSON));

        RemoteResult r = client.reverse(new LedgerReversalRequest(REF, REF + "-REVERSAL", KEY, "TRANSFER_COMPENSATION"));

        assertThat(r.outcome()).isEqualTo(Outcome.SUCCEEDED);
        server.verify();
    }

    
@Test
void ledgerRejectionAndTimeoutAreClassified() {
    server.expect(requestTo(BASE + "/internal/journal-entries"))
            .andRespond(withStatus(HttpStatus.UNPROCESSABLE_ENTITY)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body("{\"code\":\"UNBALANCED_ENTRY\"}"));

    server.expect(requestTo(BASE + "/internal/journal-entries"))
            .andRespond(withException(new SocketTimeoutException("Read timed out")));

    RemoteResult rejected = client.post(post());
    assertThat(rejected.outcome()).isEqualTo(Outcome.REJECTED);
    assertThat(rejected.code()).isEqualTo("UNBALANCED_ENTRY");

    assertThat(client.post(post()).outcome()).isEqualTo(Outcome.UNKNOWN);

    server.verify();
}

@Test
void postingLookupMapsPostedReversedAndMissing() {
    server.expect(requestTo(BASE + "/internal/journal-entries/by-reference/" + REF))
            .andExpect(method(HttpMethod.GET))
            .andRespond(withSuccess("{\"status\":\"REVERSED\"}", MediaType.APPLICATION_JSON));

    server.expect(requestTo(BASE + "/internal/journal-entries/by-reference/" + REF))
            .andRespond(withStatus(HttpStatus.NOT_FOUND));

    assertThat(client.getPosting(REF).outcome()).isEqualTo(Outcome.SUCCEEDED);
    assertThat(client.getPosting(REF).outcome()).isEqualTo(Outcome.NOT_FOUND);

    server.verify();
}

}
