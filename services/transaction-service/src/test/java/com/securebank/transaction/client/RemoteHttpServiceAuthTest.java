package com.securebank.transaction.client;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.securebank.transaction.client.RemoteResult.Outcome;
import com.securebank.transaction.config.RemoteClientProperties;
import java.math.BigDecimal;
import java.time.Clock;
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

/** How the service token reaches (or fails to reach) the wire, using the real OAuth2 provider and stub endpoint. */
class RemoteHttpServiceAuthTest {

    private static final String BASE = "http://ledger.test";

    private MockRestServiceServer server;
    private RestClient restClient;
    private RemoteClientProperties.Ledger cfg;

    @BeforeEach
    void setUp() {
        RestClient.Builder builder = RestClient.builder().baseUrl(BASE);
        server = MockRestServiceServer.bindTo(builder).build();
        restClient = builder.build();
        cfg = new RemoteClientProperties.Ledger(BASE, Duration.ofSeconds(1), Duration.ofSeconds(1),
                "/internal/journal-entries", "/internal/journal-entries/{businessReference}/reversals",
                "/internal/journal-entries/by-reference/{businessReference}");
    }

    private LedgerServiceHttpClient ledgerClient(ServiceTokenProvider tokens) {
        return new LedgerServiceHttpClient(new RemoteHttp(restClient, tokens), cfg, new ObjectMapper());
    }

    private static OAuth2ClientCredentialsTokenProvider oauthProvider(
            OAuth2ClientCredentialsTokenProviderTest.StubTokenEndpoint endpoint) {
        var registration = OAuth2ClientCredentialsTokenProvider.registration(new RemoteClientProperties.Auth(
                RemoteClientProperties.Auth.Mode.OAUTH2_CLIENT_CREDENTIALS, "", "svc", "sentinel-client-credential", "",
                "http://kc.test/realms/securebank", ""));
        return new OAuth2ClientCredentialsTokenProvider(OAuth2ClientCredentialsTokenProvider.createManager(
                registration, endpoint, Clock.systemUTC(), Duration.ofSeconds(60)));
    }

    private static LedgerPostRequest post() {
        return new LedgerPostRequest("TRANSFER-" + UUID.randomUUID(), UUID.randomUUID(), "Transfer", "INR",
                List.of(new LedgerEntryLine(UUID.randomUUID(), "DEBIT", new BigDecimal("10.00")),
                        new LedgerEntryLine(UUID.randomUUID(), "CREDIT", new BigDecimal("10.00"))));
    }

    @Test
    void oauthAccessTokenIsAttachedAsABearerTokenOnEveryCall() {
        var endpoint = new OAuth2ClientCredentialsTokenProviderTest.StubTokenEndpoint();
        LedgerServiceHttpClient ledger = ledgerClient(oauthProvider(endpoint));
        server.expect(requestTo(BASE + "/internal/journal-entries")).andExpect(method(HttpMethod.POST))
                .andExpect(header("Authorization", "Bearer access-token-1"))
                .andRespond(withSuccess("{\"status\":\"POSTED\"}", MediaType.APPLICATION_JSON));
        server.expect(requestTo(BASE + "/internal/journal-entries/by-reference/TRANSFER-X"))
                .andExpect(header("Authorization", "Bearer access-token-1"))
                .andRespond(withSuccess("{\"status\":\"POSTED\"}", MediaType.APPLICATION_JSON));

        assertThat(ledger.post(post()).outcome()).isEqualTo(Outcome.SUCCEEDED);
        assertThat(ledger.getPosting("TRANSFER-X").outcome()).isEqualTo(Outcome.SUCCEEDED);
        assertThat(endpoint.calls).hasValue(1);           // cached between the two calls
        server.verify();
    }

    @Test
    void tokenFailureSendsNothingAndIsRetryableNeitherSuccessNorUnknown() {
        var endpoint = new OAuth2ClientCredentialsTokenProviderTest.StubTokenEndpoint();
        endpoint.failure = new IllegalStateException("identity provider down");
        LedgerServiceHttpClient ledger = ledgerClient(oauthProvider(endpoint));
        // No expectations registered: MockRestServiceServer fails the test if any HTTP request is attempted.

        RemoteResult submit = ledger.post(post());
        assertThat(submit.outcome()).isEqualTo(Outcome.RETRYABLE_FAILURE);
        assertThat(ledger.reverse(new LedgerReversalRequest("TRANSFER-A", "TRANSFER-A-REVERSAL", UUID.randomUUID(),
                "TRANSFER_COMPENSATION")).outcome()).isEqualTo(Outcome.RETRYABLE_FAILURE);
        // A failed lookup is "not known", never "not found" (which would allow a blind resend).
        assertThat(ledger.getPosting("TRANSFER-A").outcome()).isEqualTo(Outcome.UNKNOWN);
        server.verify();
    }

    @Test
    void modeNoneSendsNoAuthorizationHeaderAndASecuredLedgerAnswerIsRetryableNotSuccess() {
        LedgerServiceHttpClient ledger = ledgerClient(Optional::empty);
        server.expect(requestTo(BASE + "/internal/journal-entries"))
                .andExpect(request -> assertThat(request.getHeaders().containsKey("Authorization")).isFalse())
                .andRespond(withStatus(HttpStatus.UNAUTHORIZED).contentType(MediaType.APPLICATION_JSON)
                        .body("{\"code\":\"UNAUTHORIZED\"}"));

        RemoteResult result = ledger.post(post());

        assertThat(result.outcome()).isEqualTo(Outcome.RETRYABLE_FAILURE);
        assertThat(result.code()).isEqualTo("SERVICE_AUTH_FAILED");
        server.verify();
    }

    @Test
    void forbiddenFromTheLedgerIsAlsoRetryableBecauseRolesCanBeFixedWithoutLosingTheTransfer() {
        LedgerServiceHttpClient ledger = ledgerClient(() -> Optional.of("token-without-roles"));
        server.expect(requestTo(BASE + "/internal/journal-entries"))
                .andRespond(withStatus(HttpStatus.FORBIDDEN).contentType(MediaType.APPLICATION_JSON)
                        .body("{\"code\":\"ACCESS_DENIED\"}"));

        assertThat(ledger.post(post()).outcome()).isEqualTo(Outcome.RETRYABLE_FAILURE);
        server.verify();
    }
}
