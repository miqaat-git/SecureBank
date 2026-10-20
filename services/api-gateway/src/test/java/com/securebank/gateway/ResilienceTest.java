package com.securebank.gateway;

import static org.assertj.core.api.Assertions.assertThat;

import com.securebank.gateway.support.AbstractGatewayTest;
import com.securebank.gateway.support.Downstreams;
import com.securebank.gateway.support.GatewayTest;
import com.securebank.gateway.support.TestJwtConfig;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.reactive.server.EntityExchangeResult;

@GatewayTest
class ResilienceTest extends AbstractGatewayTest {

    private static final String CID = "X-Correlation-Id";

    @Test
    void correlationIdIsGeneratedAndPropagatedDownstream() {
        EntityExchangeResult<byte[]> result = client.get().uri("/api/customers/1")
                .header("Authorization", TestJwtConfig.BEARER)
                .exchange()
                .expectStatus().isOk()
                .expectBody().returnResult();

        String returned = result.getResponseHeaders().getFirst(CID);
        assertThat(returned).isNotNull();
        assertThat(UUID.fromString(returned)).isNotNull();
        assertThat(Downstreams.CUSTOMER.lastHeader(CID)).isEqualTo(returned);
    }

    @Test
    void wellFormedInboundCorrelationIdIsPreserved() {
        client.get().uri("/api/ledger/1")
                .header("Authorization", TestJwtConfig.BEARER)
                .header(CID, "client-req-12345")
                .exchange()
                .expectStatus().isOk()
                .expectHeader().valueEquals(CID, "client-req-12345");
        assertThat(Downstreams.LEDGER.lastHeader(CID)).isEqualTo("client-req-12345");
    }

    @Test
    void malformedInboundCorrelationIdIsReplaced() {
        EntityExchangeResult<byte[]> result = client.get().uri("/api/ledger/1")
                .header("Authorization", TestJwtConfig.BEARER)
                .header(CID, "bad id with spaces <script>")
                .exchange()
                .expectStatus().isOk()
                .expectBody().returnResult();

        String returned = result.getResponseHeaders().getFirst(CID);
        assertThat(returned).doesNotContain("script").doesNotContain(" ");
        assertThat(UUID.fromString(returned)).isNotNull();
        assertThat(Downstreams.LEDGER.lastHeader(CID)).isEqualTo(returned);
    }

    @Test
    void gatewayGeneratedErrorsCarryTheCorrelationId() {
        EntityExchangeResult<byte[]> result = client.get().uri("/api/customers/1")
                .exchange()
                .expectStatus().isUnauthorized()
                .expectBody()
                .jsonPath("$.correlationId").isNotEmpty()
                .returnResult();
        assertThat(result.getResponseHeaders().getFirst(CID)).isNotBlank();
    }

    @Test
    void slowDownstreamYieldsSanitizedGatewayTimeout() {
        client.get().uri("/api/ledger/slow")
                .header("Authorization", TestJwtConfig.BEARER)
                .exchange()
                .expectStatus().isEqualTo(504)
                .expectBody(String.class)
                .value(body -> assertSanitized(body, "GATEWAY_TIMEOUT"));
    }

    @Test
    void financialPostIsNeverRetriedAfterDownstreamFailureResponse() {
        client.post().uri("/api/transactions/unavailable")
                .header("Authorization", TestJwtConfig.BEARER)
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue("{\"amount\":10}")
                .exchange()
                .expectStatus().isEqualTo(503)
                .expectBody()
                // downstream's own safe response is preserved, not replaced by a gateway error
                .jsonPath("$.code").isEqualTo("DOWNSTREAM_DOWN");

        assertThat(Downstreams.TRANSACTION.postCount()).isEqualTo(1);
    }

    @Test
    void financialPostIsNeverRetriedAfterTimeout() {
        client.post().uri("/api/transactions/slow")
                .header("Authorization", TestJwtConfig.BEARER)
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue("{\"amount\":10}")
                .exchange()
                .expectStatus().isEqualTo(504);

        assertThat(Downstreams.TRANSACTION.postCount()).isEqualTo(1);
    }

    @Test
    void oversizedRequestIsRejectedBeforeRouting() {
        client.post().uri("/api/accounts")
                .header("Authorization", TestJwtConfig.BEARER)
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue("x".repeat(2048)) // limit in test profile is 1KB
                .exchange()
                .expectStatus().isEqualTo(413)
                .expectBody()
                .jsonPath("$.code").isEqualTo("PAYLOAD_TOO_LARGE");

        assertThat(Downstreams.ACCOUNT.requestCount()).isZero();
    }

    static void assertSanitized(String body, String expectedCode) {
        assertThat(body).contains(expectedCode)
                .doesNotContain("127.0.0.1")
                .doesNotContain("localhost")
                .doesNotContain("Exception")
                .doesNotContain("refused")
                .doesNotContain("at com.")
                .doesNotContain("at io.")
                .doesNotContain("stackTrace");
    }
}
