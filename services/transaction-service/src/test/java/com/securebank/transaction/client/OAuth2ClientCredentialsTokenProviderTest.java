package com.securebank.transaction.client;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.securebank.transaction.config.RemoteClientProperties;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.client.endpoint.OAuth2AccessTokenResponseClient;
import org.springframework.security.oauth2.client.endpoint.OAuth2ClientCredentialsGrantRequest;
import org.springframework.security.oauth2.client.registration.ClientRegistration;
import org.springframework.security.oauth2.core.OAuth2AccessToken;
import org.springframework.security.oauth2.core.OAuth2AuthorizationException;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.core.endpoint.OAuth2AccessTokenResponse;

/**
 * Token acquisition, caching, renewal and failure handling with the REAL Spring Security authorized-client manager
 * but a stub token endpoint and a controllable clock: no network, no Keycloak.
 */
class OAuth2ClientCredentialsTokenProviderTest {

    private static final String SECRET = "sentinel-value-must-never-be-logged";

    /** Deterministic token endpoint that records how often and with which registration it was called. */
    static final class StubTokenEndpoint
            implements OAuth2AccessTokenResponseClient<OAuth2ClientCredentialsGrantRequest> {
        final AtomicInteger calls = new AtomicInteger();
        volatile RuntimeException failure;
        volatile ClientRegistration lastRegistration;

        @Override
        public OAuth2AccessTokenResponse getTokenResponse(OAuth2ClientCredentialsGrantRequest request) {
            calls.incrementAndGet();
            lastRegistration = request.getClientRegistration();
            if (failure != null) {
                throw failure;
            }
            return OAuth2AccessTokenResponse.withToken("access-token-" + calls.get())
                    .tokenType(OAuth2AccessToken.TokenType.BEARER).expiresIn(300).build();
        }
    }

    static final class MutableClock extends Clock {
        private Instant now = Instant.now();

        void advance(Duration duration) {
            now = now.plus(duration);
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }
    }

    private StubTokenEndpoint endpoint;
    private MutableClock clock;
    private OAuth2ClientCredentialsTokenProvider provider;

    private static RemoteClientProperties.Auth auth(String clientId, String secret, String tokenUri, String issuer,
                                                    String scope) {
        return new RemoteClientProperties.Auth(RemoteClientProperties.Auth.Mode.OAUTH2_CLIENT_CREDENTIALS, "",
                clientId, secret, tokenUri, issuer, scope);
    }

    @BeforeEach
    void setUp() {
        endpoint = new StubTokenEndpoint();
        clock = new MutableClock();
        ClientRegistration registration = OAuth2ClientCredentialsTokenProvider.registration(
                auth("securebank-transaction-service", SECRET, "", "http://keycloak.test/realms/securebank", ""));
        provider = new OAuth2ClientCredentialsTokenProvider(OAuth2ClientCredentialsTokenProvider.createManager(
                registration, endpoint, clock, Duration.ofSeconds(60)));
    }

    @Test
    void obtainsATokenFromTheConfiguredClientRegistration() {
        assertThat(provider.bearerToken()).contains("access-token-1");

        ClientRegistration used = endpoint.lastRegistration;
        assertThat(used.getClientId()).isEqualTo("securebank-transaction-service");
        assertThat(used.getClientSecret()).isEqualTo(SECRET);
        assertThat(used.getProviderDetails().getTokenUri())
                .isEqualTo("http://keycloak.test/realms/securebank/protocol/openid-connect/token");
    }

    @Test
    void cachesTheTokenUntilItIsAboutToExpireThenRenewsIt() {
        assertThat(provider.bearerToken()).contains("access-token-1");
        assertThat(provider.bearerToken()).contains("access-token-1");
        clock.advance(Duration.ofSeconds(200));                       // 300s lifetime, still valid
        assertThat(provider.bearerToken()).contains("access-token-1");
        assertThat(endpoint.calls).hasValue(1);

        clock.advance(Duration.ofSeconds(50));                        // inside the 60s skew window: renew
        assertThat(provider.bearerToken()).contains("access-token-2");
        assertThat(endpoint.calls).hasValue(2);

        clock.advance(Duration.ofSeconds(600));                       // fully expired: renew again
        assertThat(provider.bearerToken()).contains("access-token-3");
        assertThat(endpoint.calls).hasValue(3);
    }

    @Test
    void identityProviderRejectionBecomesASafeServiceTokenException() {
        endpoint.failure = new OAuth2AuthorizationException(
                new OAuth2Error("invalid_client", "client secret " + SECRET + " is wrong", null));

        assertThatThrownBy(provider::bearerToken)
                .isInstanceOf(ServiceTokenException.class)
                .hasMessage("SERVICE_TOKEN_UNAVAILABLE")
                .hasNoCause()
                .satisfies(e -> assertThat(e.toString()).doesNotContain(SECRET));
    }

    @Test
    void transportFailuresAlsoBecomeServiceTokenExceptionsAndRecoverLater() {
        endpoint.failure = new IllegalStateException("connection refused to keycloak, secret " + SECRET);
        assertThatThrownBy(provider::bearerToken).isInstanceOf(ServiceTokenException.class)
                .hasMessage("SERVICE_TOKEN_UNAVAILABLE").hasNoCause();

        endpoint.failure = null;                                      // identity provider is back
        assertThat(provider.bearerToken()).isPresent();
    }

    // ------------------------------------------------------------ configuration validation

    @Test
    void configurationValidationNamesTheMissingVariableButNeverEchoesASecret() {
        assertThatThrownBy(() -> OAuth2ClientCredentialsTokenProvider.registration(
                auth("", SECRET, "", "http://kc.test/realms/securebank", "")))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("SERVICE_AUTH_CLIENT_ID")
                .hasMessageNotContaining(SECRET);
        assertThatThrownBy(() -> OAuth2ClientCredentialsTokenProvider.registration(
                auth("svc", "  ", "", "http://kc.test/realms/securebank", "")))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("SERVICE_AUTH_CLIENT_SECRET");
        assertThatThrownBy(() -> OAuth2ClientCredentialsTokenProvider.registration(auth("svc", SECRET, "", "", "")))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("SERVICE_AUTH_ISSUER_URI")
                .hasMessageNotContaining(SECRET);
    }

    @Test
    void explicitTokenUriWinsOverTheIssuerAndScopesAreSplit() {
        ClientRegistration registration = OAuth2ClientCredentialsTokenProvider.registration(
                auth("svc", SECRET, "http://kc.test/custom/token", "http://ignored.test/realms/x", "a b,c"));

        assertThat(registration.getProviderDetails().getTokenUri()).isEqualTo("http://kc.test/custom/token");
        assertThat(registration.getScopes()).containsExactlyInAnyOrder("a", "b", "c");
    }

    @Test
    void propertiesNeverPrintSecrets() {
        String text = auth("svc", SECRET, "", "http://kc.test/realms/securebank/", "").toString();

        assertThat(text).doesNotContain(SECRET).contains("redacted")
                .contains("http://kc.test/realms/securebank/protocol/openid-connect/token");
    }
}
