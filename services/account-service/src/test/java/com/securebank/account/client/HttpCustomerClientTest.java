package com.securebank.account.client;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withResourceNotFound;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import com.securebank.account.config.AccountProperties;
import com.securebank.account.exception.ApiException;
import com.securebank.account.exception.ErrorCode;
import java.io.IOException;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

/** Customer Service integration: success and every failure mode, against a mocked HTTP server. */
class HttpCustomerClientTest {

    private static final String BASE = "http://customer-service.test";

    private final UUID customerId = UUID.randomUUID();
    private MockRestServiceServer server;
    private HttpCustomerClient client;

    @BeforeEach
    void setUp() {
        RestClient.Builder builder = RestClient.builder().baseUrl(BASE);
        server = MockRestServiceServer.bindTo(builder).build();
        AccountProperties properties = new AccountProperties(
                new AccountProperties.CustomerService(BASE, "/api/v1/customers/{customerId}",
                        Duration.ofSeconds(2), Duration.ofSeconds(3), List.of("ACTIVE"), true),
                new AccountProperties.Security("https://issuer.invalid.test", "", "customer_id",
                        "securebank-account-service"),
                new AccountProperties.Limits(5));
        client = new HttpCustomerClient(builder.build(), properties);
    }

    @AfterEach
    void clearSecurityContext() {
        SecurityContextHolder.clearContext();
    }

    private String url() {
        return BASE + "/api/v1/customers/" + customerId;
    }

    @Test
    void returnsTheCustomerAndIgnoresUnknownFields() {
        server.expect(requestTo(url())).andExpect(method(HttpMethod.GET))
                .andRespond(withSuccess("{\"id\":\"" + customerId
                        + "\",\"status\":\"ACTIVE\",\"fullName\":\"Ignored Person\",\"email\":\"x@example.test\"}",
                        MediaType.APPLICATION_JSON));

        Optional<CustomerSummary> result = client.findCustomer(customerId);

        assertThat(result).isPresent();
        assertThat(result.get().id()).isEqualTo(customerId);
        assertThat(result.get().status()).isEqualTo("ACTIVE");
        server.verify();
    }

    @Test
    void notFoundMeansTheCustomerDoesNotExist() {
        server.expect(requestTo(url())).andRespond(withResourceNotFound());
        assertThat(client.findCustomer(customerId)).isEmpty();
    }

    @Test
    void serverErrorIsReportedAsUnavailableNeverAsExistingCustomer() {
        server.expect(requestTo(url())).andRespond(withStatus(HttpStatus.INTERNAL_SERVER_ERROR));
        assertUnavailable();
    }

    @Test
    void unauthorizedFromCustomerServiceIsUnavailableToo() {
        server.expect(requestTo(url())).andRespond(withStatus(HttpStatus.UNAUTHORIZED));
        assertUnavailable();
    }

    @Test
    void connectionFailureOrTimeoutIsUnavailable() {
        server.expect(requestTo(url())).andRespond(request -> {
            throw new IOException("simulated connect/read timeout");
        });
        assertUnavailable();
    }

    @Test
    void emptyBodyIsUnavailable() {
        server.expect(requestTo(url())).andRespond(withSuccess());
        assertUnavailable();
    }

    @Test
    void mismatchingIdInPayloadIsUnavailable() {
        server.expect(requestTo(url())).andRespond(withSuccess(
                "{\"id\":\"" + UUID.randomUUID() + "\",\"status\":\"ACTIVE\"}", MediaType.APPLICATION_JSON));
        assertUnavailable();
    }

    @Test
    void payloadWithoutStatusIsUnavailable() {
        server.expect(requestTo(url())).andRespond(withSuccess(
                "{\"id\":\"" + customerId + "\"}", MediaType.APPLICATION_JSON));
        assertUnavailable();
    }

    @Test
    void forwardsTheCallersBearerToken() {
        Jwt jwt = Jwt.withTokenValue("caller-token-value").header("alg", "RS256").subject("admin-1").build();
        SecurityContextHolder.getContext().setAuthentication(new JwtAuthenticationToken(jwt));
        server.expect(requestTo(url())).andExpect(header("Authorization", "Bearer caller-token-value"))
                .andRespond(withSuccess("{\"id\":\"" + customerId + "\",\"status\":\"ACTIVE\"}",
                        MediaType.APPLICATION_JSON));

        assertThat(client.findCustomer(customerId)).isPresent();
        server.verify();
    }

    private void assertUnavailable() {
        assertThatThrownBy(() -> client.findCustomer(customerId)).isInstanceOfSatisfying(ApiException.class,
                e -> assertThat(e.getErrorCode()).isEqualTo(ErrorCode.CUSTOMER_SERVICE_UNAVAILABLE));
    }
}
