package com.securebank.account.client;

import com.securebank.account.config.AccountProperties;
import com.securebank.account.exception.ApiException;
import com.securebank.account.exception.ErrorCode;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

/**
 * Calls the Customer Service over HTTP.
 *
 * <p>Expected contract (configurable, see README): {@code GET {baseUrl}{customerPath}} (default /internal/v1/customers/{id}/eligibility) with the customer UUID.
 * <ul>
 *   <li>200 with JSON {@code {"id": "<uuid>", "status": "ACTIVE", ...}} (extra fields are ignored)</li>
 *   <li>404 when the customer does not exist</li>
 * </ul>
 * The call carries this service's own client-credentials token (never the end user's). 401/403 from the Customer
 * Service mean the credential or role is misconfigured. Anything else (5xx, 401/403, timeouts, malformed body,
 * mismatching id, no service token) is reported as
 * CUSTOMER_SERVICE_UNAVAILABLE: the customer is never assumed to exist.
 */
@Component
public class HttpCustomerClient implements CustomerClient {

    private static final Logger log = LoggerFactory.getLogger(HttpCustomerClient.class);

    private final RestClient restClient;
    private final AccountProperties.CustomerService config;
    private final ServiceTokenProvider tokens;

    public HttpCustomerClient(RestClient customerServiceRestClient, AccountProperties properties,
                              ServiceTokenProvider customerServiceTokenProvider) {
        this.restClient = customerServiceRestClient;
        this.config = properties.customerService();
        this.tokens = customerServiceTokenProvider;
    }

    @Override
    public Optional<CustomerSummary> findCustomer(UUID customerId) {
        Optional<String> serviceToken;
        try {
            serviceToken = tokens.bearerToken();
        } catch (ServiceTokenException ex) {
            log.warn("No service token for the Customer Service call ({})", ex.getMessage());
            throw unavailable(null);
        }
        try {
            CustomerSummary summary = restClient.get()
                    .uri(config.customerPath(), customerId)
                    // Always this service's own credential. The end user's token is never forwarded, and no
                    // customer-controlled value is used as proof of anything: the id comes from the validated token.
                    .headers(headers -> serviceToken.ifPresent(headers::setBearerAuth))
                    .retrieve()
                    .body(CustomerSummary.class);
            if (summary == null || summary.id() == null || !customerId.equals(summary.id())
                    || summary.status() == null) {
                log.warn("Customer Service returned an unusable payload for customer {}", customerId);
                throw unavailable(null);
            }
            return Optional.of(summary);
        } catch (HttpClientErrorException.NotFound notFound) {
            return Optional.empty();
        } catch (HttpClientErrorException.Unauthorized | HttpClientErrorException.Forbidden denied) {
            // Misconfigured service credential or role: an operator problem, never "customer not found".
            log.error("Customer Service refused this service's credential (HTTP {}); check the account-service "
                    + "client and role", denied.getStatusCode().value());
            throw unavailable(null);
        } catch (RestClientException ex) {
            // Covers other 4xx, 5xx, I/O errors and timeouts.
            log.warn("Customer Service call failed for customer {}: {}", customerId, ex.getClass().getSimpleName());
            throw unavailable(ex);
        }
    }

    private static ApiException unavailable(Throwable cause) {
        return new ApiException(ErrorCode.CUSTOMER_SERVICE_UNAVAILABLE,
                "The Customer Service is currently unavailable. The customer could not be verified.", cause);
    }
}
