package com.securebank.account.client;

import com.securebank.account.config.AccountProperties;
import com.securebank.account.exception.ApiException;
import com.securebank.account.exception.ErrorCode;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

/**
 * Calls the Customer Service over HTTP.
 *
 * <p>Expected contract (configurable, see README): {@code GET {baseUrl}{customerPath}} with the customer UUID.
 * <ul>
 *   <li>200 with JSON {@code {"id": "<uuid>", "status": "ACTIVE", ...}} (extra fields are ignored)</li>
 *   <li>404 when the customer does not exist</li>
 * </ul>
 * Anything else (5xx, 401/403, timeouts, malformed body, mismatching id) is reported as
 * CUSTOMER_SERVICE_UNAVAILABLE: the customer is never assumed to exist.
 */
@Component
public class HttpCustomerClient implements CustomerClient {

    private static final Logger log = LoggerFactory.getLogger(HttpCustomerClient.class);

    private final RestClient restClient;
    private final AccountProperties.CustomerService config;

    public HttpCustomerClient(RestClient customerServiceRestClient, AccountProperties properties) {
        this.restClient = customerServiceRestClient;
        this.config = properties.customerService();
    }

    @Override
    public Optional<CustomerSummary> findCustomer(UUID customerId) {
        try {
            CustomerSummary summary = restClient.get()
                    .uri(config.customerPath(), customerId)
                    .headers(headers -> {
                        if (config.forwardCallerToken()) {
                            callerToken().ifPresent(headers::setBearerAuth);
                        }
                    })
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
        } catch (RestClientException ex) {
            // Covers 4xx other than 404 (e.g. 401/403 misconfiguration), 5xx, I/O errors and timeouts.
            log.warn("Customer Service call failed for customer {}: {}", customerId, ex.getClass().getSimpleName());
            throw unavailable(ex);
        }
    }

    private static Optional<String> callerToken() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication instanceof JwtAuthenticationToken jwtAuthentication) {
            return Optional.of(jwtAuthentication.getToken().getTokenValue());
        }
        return Optional.empty();
    }

    private static ApiException unavailable(Throwable cause) {
        return new ApiException(ErrorCode.CUSTOMER_SERVICE_UNAVAILABLE,
                "The Customer Service is currently unavailable. The customer could not be verified.", cause);
    }
}
