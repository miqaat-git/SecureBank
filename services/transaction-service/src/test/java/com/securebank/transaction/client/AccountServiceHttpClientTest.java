package com.securebank.transaction.client;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
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
import java.io.IOException;
import java.math.BigDecimal;
import java.net.ConnectException;
import java.net.SocketTimeoutException;
import java.time.Duration;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

class AccountServiceHttpClientTest {

    private static final String BASE = "http://account.test";
    private static final UUID ACCOUNT = UUID.randomUUID();
    private static final UUID OP = UUID.randomUUID();
    private static final UUID TRANSFER = UUID.randomUUID();

    private MockRestServiceServer server;
    private AccountServiceHttpClient client;

    @BeforeEach
    void setUp() {
        RestClient.Builder builder = RestClient.builder().baseUrl(BASE);
        server = MockRestServiceServer.bindTo(builder).build();
        RemoteClientProperties.Account cfg = new RemoteClientProperties.Account(BASE, Duration.ofSeconds(1),
                Duration.ofSeconds(1), "/api/v1/accounts/{accountId}", "/internal/accounts/{accountId}/operations",
                "/internal/accounts/{accountId}/operations/{operationId}");
        client = new AccountServiceHttpClient(new RemoteHttp(builder.build(), () -> Optional.of("svc-token")), cfg,
                new ObjectMapper());
    }

    private AccountOperationRequest debit() {
        return new AccountOperationRequest(ACCOUNT, OP, AccountOperationType.DEBIT, new BigDecimal("100.00"), "INR",
                TRANSFER, null);
    }

    @Test
    void operationIsSentWithIdempotencyKeyBearerTokenAndBodyWithoutPathFields() {
        server.expect(requestTo(BASE + "/internal/accounts/" + ACCOUNT + "/operations"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(header("Idempotency-Key", OP.toString()))
                .andExpect(header("Authorization", "Bearer svc-token"))
                .andExpect(jsonPath("$.operationId").value(OP.toString()))
                .andExpect(jsonPath("$.type").value("DEBIT"))
                .andExpect(jsonPath("$.amount").value(100.00))
                .andExpect(jsonPath("$.currency").value("INR"))
                .andExpect(jsonPath("$.transferId").value(TRANSFER.toString()))
                .andExpect(jsonPath("$.accountId").doesNotExist())
                .andRespond(withSuccess("{\"status\":\"SUCCEEDED\"}", MediaType.APPLICATION_JSON));

        assertThat(client.applyOperation(debit()).outcome()).isEqualTo(Outcome.SUCCEEDED);
        server.verify();
    }

    @Test
    void rejectionCodeIsPropagatedAndRemoteMessageIsDiscarded() {
        server.expect(requestTo(BASE + "/internal/accounts/" + ACCOUNT + "/operations"))
                .andRespond(withStatus(HttpStatus.UNPROCESSABLE_ENTITY).contentType(MediaType.APPLICATION_JSON)
                        .body("{\"code\":\"INSUFFICIENT_FUNDS\",\"message\":\"internal stack trace\"}"));

        RemoteResult r = client.applyOperation(debit());

        assertThat(r.outcome()).isEqualTo(Outcome.REJECTED);
        assertThat(r.code()).isEqualTo("INSUFFICIENT_FUNDS");
    }

    @Test
    void readTimeoutIsAnAmbiguousUnknownOutcome() {
        server.expect(requestTo(BASE + "/internal/accounts/" + ACCOUNT + "/operations"))
                .andRespond(withException(new SocketTimeoutException("Read timed out")));

        assertThat(client.applyOperation(debit()).outcome()).isEqualTo(Outcome.UNKNOWN);
    }

    @Test
    void connectionRefusedIsProvablyNotAppliedSoRetryable() {
        server.expect(requestTo(BASE + "/internal/accounts/" + ACCOUNT + "/operations"))
                .andRespond(withException(new ConnectException("Connection refused")));

        assertThat(client.applyOperation(debit()).outcome()).isEqualTo(Outcome.RETRYABLE_FAILURE);
    }

    @Test
    void genericIoFailureIsUnknown() {
        server.expect(requestTo(BASE + "/internal/accounts/" + ACCOUNT + "/operations"))
                .andRespond(withException(new IOException("Connection reset")));

        assertThat(client.applyOperation(debit()).outcome()).isEqualTo(Outcome.UNKNOWN);
    }

   
@Test
void serverErrorIsUnknownAndServiceUnavailableIsRetryable() {
    server.expect(requestTo(BASE + "/internal/accounts/" + ACCOUNT + "/operations"))
            .andRespond(withStatus(HttpStatus.INTERNAL_SERVER_ERROR));

    server.expect(requestTo(BASE + "/internal/accounts/" + ACCOUNT + "/operations"))
            .andRespond(withStatus(HttpStatus.SERVICE_UNAVAILABLE));

    assertThat(client.applyOperation(debit()).outcome()).isEqualTo(Outcome.UNKNOWN);
    assertThat(client.applyOperation(debit()).outcome()).isEqualTo(Outcome.RETRYABLE_FAILURE);

    server.verify();
}

@Test
void operationStatusLookupMapsFoundAndNotFound() {
    server.expect(requestTo(BASE + "/internal/accounts/" + ACCOUNT + "/operations/" + OP))
            .andExpect(method(HttpMethod.GET))
            .andRespond(withSuccess("{\"status\":\"SUCCEEDED\"}", MediaType.APPLICATION_JSON));

    server.expect(requestTo(BASE + "/internal/accounts/" + ACCOUNT + "/operations/" + OP))
            .andRespond(withStatus(HttpStatus.NOT_FOUND));

    assertThat(client.getOperation(ACCOUNT, OP).outcome()).isEqualTo(Outcome.SUCCEEDED);
    assertThat(client.getOperation(ACCOUNT, OP).outcome()).isEqualTo(Outcome.NOT_FOUND);

    server.verify();
}

@Test
void accountLookupReturnsEmptyOn404AndThrowsOnOtherFailures() {
    server.expect(requestTo(BASE + "/api/v1/accounts/" + ACCOUNT))
            .andRespond(withStatus(HttpStatus.NOT_FOUND));

    server.expect(requestTo(BASE + "/api/v1/accounts/" + ACCOUNT))
            .andRespond(withStatus(HttpStatus.INTERNAL_SERVER_ERROR));

    server.expect(requestTo(BASE + "/api/v1/accounts/" + ACCOUNT))
            .andRespond(withException(new SocketTimeoutException("timeout")));

    assertThat(client.findAccount(ACCOUNT)).isEmpty();

    assertThatThrownBy(() -> client.findAccount(ACCOUNT))
            .isInstanceOf(RemoteCallException.class);

    assertThatThrownBy(() -> client.findAccount(ACCOUNT))
            .isInstanceOf(RemoteCallException.class);

    server.verify();
}

}
