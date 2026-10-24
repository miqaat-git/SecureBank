package com.securebank.transaction.client;

import com.securebank.transaction.config.RemoteClientProperties;
import java.time.Clock;
import java.time.Duration;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.http.converter.FormHttpMessageConverter;
import org.springframework.security.oauth2.client.ClientAuthorizationException;
import org.springframework.security.oauth2.client.ClientCredentialsOAuth2AuthorizedClientProvider;
import org.springframework.security.oauth2.client.InMemoryOAuth2AuthorizedClientService;
import org.springframework.security.oauth2.client.OAuth2AuthorizeRequest;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClient;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClientManager;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClientService;
import org.springframework.security.oauth2.client.endpoint.OAuth2AccessTokenResponseClient;
import org.springframework.security.oauth2.client.endpoint.OAuth2ClientCredentialsGrantRequest;
import org.springframework.security.oauth2.client.endpoint.RestClientClientCredentialsTokenResponseClient;
import org.springframework.security.oauth2.client.http.OAuth2ErrorResponseErrorHandler;
import org.springframework.security.oauth2.client.registration.ClientRegistration;
import org.springframework.security.oauth2.client.registration.InMemoryClientRegistrationRepository;
import org.springframework.security.oauth2.client.AuthorizedClientServiceOAuth2AuthorizedClientManager;
import org.springframework.security.oauth2.core.AuthorizationGrantType;
import org.springframework.security.oauth2.core.ClientAuthenticationMethod;
import org.springframework.security.oauth2.core.http.converter.OAuth2AccessTokenResponseHttpMessageConverter;
import org.springframework.web.client.RestClient;

/**
 * OAuth2 client-credentials token provider backed by Spring Security's authorized-client manager, which caches the
 * access token in memory and renews it shortly before it expires (clock skew {@value #CLOCK_SKEW_SECONDS}s).
 *
 * <p>If a token cannot be obtained this throws {@link ServiceTokenException}; {@link RemoteHttp} then reports the
 * request as NOT SENT. It is never reported as a remote success and never as an ambiguous (UNKNOWN) remote outcome.
 * Nothing from the identity provider's response, the client secret or a token is logged or put in the exception.
 */
public class OAuth2ClientCredentialsTokenProvider implements ServiceTokenProvider {

    public static final String REGISTRATION_ID = "securebank-transaction-service";
    static final long CLOCK_SKEW_SECONDS = 60;
    private static final String PRINCIPAL = "securebank-transaction-service";
    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(2);
    private static final Duration READ_TIMEOUT = Duration.ofSeconds(5);

    private static final Logger log = LoggerFactory.getLogger(OAuth2ClientCredentialsTokenProvider.class);

    private final OAuth2AuthorizedClientManager manager;

    public OAuth2ClientCredentialsTokenProvider(OAuth2AuthorizedClientManager manager) {
        this.manager = manager;
    }

    /** Fail-fast creation from configuration; no network access happens until the first token is requested. */
    public static OAuth2ClientCredentialsTokenProvider create(RemoteClientProperties.Auth auth) {
        ClientRegistration registration = registration(auth);
        return new OAuth2ClientCredentialsTokenProvider(createManager(registration, defaultResponseClient(),
                Clock.systemUTC(), Duration.ofSeconds(CLOCK_SKEW_SECONDS)));
    }

    /** Validates configuration and builds the client registration. Error messages never contain secret values. */
    static ClientRegistration registration(RemoteClientProperties.Auth auth) {
        if (auth.clientId() == null || auth.clientId().isBlank()) {
            throw new IllegalStateException(
                    "securebank.clients.auth.mode=OAUTH2_CLIENT_CREDENTIALS requires SERVICE_AUTH_CLIENT_ID");
        }
        if (auth.clientSecret() == null || auth.clientSecret().isBlank()) {
            throw new IllegalStateException(
                    "securebank.clients.auth.mode=OAUTH2_CLIENT_CREDENTIALS requires SERVICE_AUTH_CLIENT_SECRET");
        }
        String tokenUri = auth.effectiveTokenUri();
        if (tokenUri.isBlank()) {
            throw new IllegalStateException("securebank.clients.auth.mode=OAUTH2_CLIENT_CREDENTIALS requires "
                    + "SERVICE_AUTH_ISSUER_URI (or SERVICE_AUTH_TOKEN_URI)");
        }
        ClientRegistration.Builder builder = ClientRegistration.withRegistrationId(REGISTRATION_ID)
                .clientId(auth.clientId().trim())
                .clientSecret(auth.clientSecret())
                .authorizationGrantType(AuthorizationGrantType.CLIENT_CREDENTIALS)
                .clientAuthenticationMethod(ClientAuthenticationMethod.CLIENT_SECRET_BASIC)
                .tokenUri(tokenUri);
        if (auth.scope() != null && !auth.scope().isBlank()) {
            builder.scope(auth.scope().trim().split("[\\s,]+"));
        }
        return builder.build();
    }

    /** Public so tests can wire a deterministic token endpoint and clock without any network access. */
    public static OAuth2AuthorizedClientManager createManager(
            ClientRegistration registration,
            OAuth2AccessTokenResponseClient<OAuth2ClientCredentialsGrantRequest> responseClient,
            Clock clock, Duration clockSkew) {
        InMemoryClientRegistrationRepository registrations = new InMemoryClientRegistrationRepository(registration);
        OAuth2AuthorizedClientService clients = new InMemoryOAuth2AuthorizedClientService(registrations);
        ClientCredentialsOAuth2AuthorizedClientProvider provider = new ClientCredentialsOAuth2AuthorizedClientProvider();
        provider.setAccessTokenResponseClient(responseClient);
        provider.setClock(clock);
        provider.setClockSkew(clockSkew);
        AuthorizedClientServiceOAuth2AuthorizedClientManager manager =
                new AuthorizedClientServiceOAuth2AuthorizedClientManager(registrations, clients);
        manager.setAuthorizedClientProvider(provider);
        return manager;
    }

    private static OAuth2AccessTokenResponseClient<OAuth2ClientCredentialsGrantRequest> defaultResponseClient() {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout((int) CONNECT_TIMEOUT.toMillis());
        factory.setReadTimeout((int) READ_TIMEOUT.toMillis());
        RestClient restClient = RestClient.builder()
                .requestFactory(factory)
                .messageConverters(converters -> {
                    converters.clear();
                    converters.add(new FormHttpMessageConverter());
                    converters.add(new OAuth2AccessTokenResponseHttpMessageConverter());
                })
                .defaultStatusHandler(new OAuth2ErrorResponseErrorHandler())
                .build();
        RestClientClientCredentialsTokenResponseClient client = new RestClientClientCredentialsTokenResponseClient();
        client.setRestClient(restClient);
        return client;
    }

    @Override
    public synchronized Optional<String> bearerToken() {
        OAuth2AuthorizedClient client;
        try {
            client = manager.authorize(OAuth2AuthorizeRequest.withClientRegistrationId(REGISTRATION_ID)
                    .principal(PRINCIPAL).build());
        } catch (ClientAuthorizationException e) {
            // Log only the OAuth2 error code (e.g. invalid_client), never the description or any secret.
            log.warn("Service token request was rejected by the identity provider (error={})",
                    e.getError() == null ? "unknown" : e.getError().getErrorCode());
            throw new ServiceTokenException("SERVICE_TOKEN_UNAVAILABLE");
        } catch (RuntimeException e) {
            log.warn("Service token could not be obtained ({})", e.getClass().getSimpleName());
            throw new ServiceTokenException("SERVICE_TOKEN_UNAVAILABLE");
        }
        if (client == null || client.getAccessToken() == null) {
            throw new ServiceTokenException("SERVICE_TOKEN_UNAVAILABLE");
        }
        return Optional.of(client.getAccessToken().getTokenValue());
    }
}
