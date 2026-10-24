package com.securebank.gateway;

import static org.assertj.core.api.Assertions.assertThat;

import com.securebank.gateway.support.AbstractGatewayTest;
import com.securebank.gateway.support.Downstreams;
import com.securebank.gateway.support.GatewayTest;
import com.securebank.gateway.support.TestJwtConfig;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.cloud.gateway.route.Route;
import org.springframework.cloud.gateway.route.RouteLocator;
import org.springframework.http.MediaType;

@GatewayTest
class RoutingTest extends AbstractGatewayTest {

    @Autowired
    RouteLocator routeLocator;

    @Test
    void routeTableTargetsConfiguredServices() {
        List<Route> routes = routeLocator.getRoutes().collectList().block();
        Map<String, String> uris = new HashMap<>();
        routes.forEach(r -> uris.put(r.getId(), r.getUri().toString()));

        assertThat(uris).containsEntry("customer-service", Downstreams.CUSTOMER.baseUrl())
                .containsEntry("account-service", Downstreams.ACCOUNT.baseUrl())
                .containsEntry("account-numbers", Downstreams.ACCOUNT.baseUrl())
                .containsEntry("account-admin", Downstreams.ACCOUNT.baseUrl())
                .containsEntry("transaction-service", Downstreams.TRANSACTION.baseUrl())
                .containsEntry("ledger-service", Downstreams.LEDGER.baseUrl());
        // Exactly these routes exist: in particular nothing routes /internal/** or the raw /api/v1/** service paths.
        assertThat(uris).hasSize(6);
    }

    @ParameterizedTest
    @CsvSource({
            "/api/customers/42?x=1,customer,/api/v1/customers/42,x=1",
            "/api/accounts/7/balance,account,/api/v1/accounts/7/balance,",
            "/api/transactions/abc?page=2&size=5,transaction,/api/v1/transfers/abc,page=2&size=5",
            "/api/ledger/entries,ledger,/api/v1/entries,"
    })
    void forwardsPathQueryAndBearerTokenUnchanged(String uri, String service, String expectedPath, String expectedQuery) {
        client.get().uri(uri)
                .header("Authorization", TestJwtConfig.BEARER)
                .exchange()
                .expectStatus().isOk()
                .expectHeader().exists("X-Correlation-Id")
                .expectBody()
                .jsonPath("$.service").isEqualTo(service)
                .jsonPath("$.method").isEqualTo("GET")
                .jsonPath("$.path").isEqualTo(expectedPath)
                .jsonPath("$.authorization").isEqualTo(TestJwtConfig.BEARER);

        assertThat(Downstreams.byName(service).requestCount()).isEqualTo(1);
        if (expectedQuery != null) {
            client.get().uri(uri).header("Authorization", TestJwtConfig.BEARER)
                    .exchange().expectBody().jsonPath("$.query").isEqualTo(expectedQuery);
        }
    }

    @Test
    void rootOfEachPrefixIsRoutedAndBodiesAreForwarded() {
        client.post().uri("/api/accounts")
                .header("Authorization", TestJwtConfig.BEARER)
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue("{\"a\":1}")
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.service").isEqualTo("account")
                .jsonPath("$.method").isEqualTo("POST")
                .jsonPath("$.path").isEqualTo("/api/v1/accounts")
                .jsonPath("$.body").isEqualTo("{\"a\":1}");
    }

    @Test
    void downstreamBusinessFailureIsPreservedNotRewrittenAsGatewayError() {
        client.get().uri("/api/transactions/business-error")
                .header("Authorization", TestJwtConfig.BEARER)
                .exchange()
                .expectStatus().isEqualTo(409)
                .expectBody()
                .jsonPath("$.code").isEqualTo("INSUFFICIENT_FUNDS");
    }

    @Test
    void unknownRouteReturnsSanitizedNotFound() {
        client.get().uri("/api/unknown/thing")
                .header("Authorization", TestJwtConfig.BEARER)
                .exchange()
                .expectStatus().isNotFound()
                .expectBody()
                .jsonPath("$.code").isEqualTo("ROUTE_NOT_FOUND")
                .jsonPath("$.correlationId").isNotEmpty()
                .jsonPath("$.trace").doesNotExist()
                .jsonPath("$.exception").doesNotExist();
    }
}
