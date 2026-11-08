package com.securebank.account.config;

import com.securebank.account.client.OAuth2ClientCredentialsTokenProvider;
import com.securebank.account.client.ServiceTokenProvider;
import java.util.Optional;
import org.springframework.context.annotation.Bean;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

/** Builds the RestClient used to call Customer Service, with explicit connect and read timeouts. */
@Configuration
public class CustomerClientConfig {

    /** Fail-fast: with OAUTH2_CLIENT_CREDENTIALS a missing client id, secret or issuer stops the start-up. */
    @Bean
    public ServiceTokenProvider customerServiceTokenProvider(AccountProperties properties, Environment environment) {
        AccountProperties.CustomerService.Auth auth = properties.customerService().auth();
        if (auth.mode() == AccountProperties.CustomerService.Auth.Mode.OAUTH2_CLIENT_CREDENTIALS) {
            return OAuth2ClientCredentialsTokenProvider.create(auth);
        }
        // NONE sends no credential at all. It exists for tests and local stubs only: outside the "test" and
        // "local" profiles a stray ACCOUNT_SERVICE_AUTH_MODE=NONE must stop the start-up, not silently degrade.
        if (!environment.acceptsProfiles(Profiles.of("test", "local"))) {
            throw new IllegalStateException("account.customer-service.auth.mode=NONE is only allowed in the "
                    + "'test' or 'local' profile; use OAUTH2_CLIENT_CREDENTIALS (ACCOUNT_SERVICE_AUTH_CLIENT_SECRET)");
        }
        return Optional::empty;
    }

    @Bean
    public RestClient customerServiceRestClient(AccountProperties properties) {
        AccountProperties.CustomerService config = properties.customerService();
        SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
        requestFactory.setConnectTimeout((int) config.connectTimeout().toMillis());
        requestFactory.setReadTimeout((int) config.readTimeout().toMillis());
        return RestClient.builder()
                .baseUrl(config.baseUrl())
                .requestFactory(requestFactory)
                .requestInterceptor(new CorrelationIdClientInterceptor())
                .build();
    }
}
