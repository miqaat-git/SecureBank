package com.securebank.transaction.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.securebank.transaction.client.OAuth2ClientCredentialsTokenProvider;
import com.securebank.transaction.client.ServiceTokenProvider;
import com.securebank.transaction.client.StaticServiceTokenProvider;
import com.securebank.transaction.config.RemoteClientProperties.Auth;
import com.securebank.transaction.config.RemoteClientProperties.Auth.Mode;
import org.junit.jupiter.api.Test;

/** Which token provider each mode wires, and fail-fast configuration validation. No network access. */
class RemoteClientsConfigTest {

    private final RemoteClientsConfig config = new RemoteClientsConfig();

    private ServiceTokenProvider provider(Auth auth) {
        return config.serviceTokenProvider(new RemoteClientProperties(null, null, auth));
    }

    private static Auth auth(Mode mode, String staticToken, String clientId, String secret, String issuer) {
        return new Auth(mode, staticToken, clientId, secret, "", issuer, "");
    }

    @Test
    void modeNoneProvidesNoTokenAndIsNotOauth() {
        ServiceTokenProvider provider = provider(auth(Mode.NONE, "", "", "", ""));

        assertThat(provider).isInstanceOf(StaticServiceTokenProvider.class);
        assertThat(provider.bearerToken()).isEmpty();
    }

    @Test
    void staticBearerModeProvidesTheConfiguredTokenAndRequiresOne() {
        assertThat(provider(auth(Mode.STATIC_BEARER, "tok-1", "", "", "")).bearerToken()).contains("tok-1");
        assertThatThrownBy(() -> provider(auth(Mode.STATIC_BEARER, " ", "", "", "")))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("SERVICE_AUTH_TOKEN");
    }

    @Test
    void oauthModeWiresTheClientCredentialsProviderWithoutContactingTheIdentityProvider() {
        ServiceTokenProvider provider = provider(auth(Mode.OAUTH2_CLIENT_CREDENTIALS, "", "svc", "sentinel-client-credential",
                "http://localhost:8180/realms/securebank"));

        assertThat(provider).isInstanceOf(OAuth2ClientCredentialsTokenProvider.class);
    }

    @Test
    void oauthModeFailsFastWhenCredentialsOrIssuerAreMissing() {
        assertThatThrownBy(() -> provider(auth(Mode.OAUTH2_CLIENT_CREDENTIALS, "", "svc", "", "http://kc/realms/x")))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("SERVICE_AUTH_CLIENT_SECRET");
        assertThatThrownBy(() -> provider(auth(Mode.OAUTH2_CLIENT_CREDENTIALS, "", "svc", "sentinel-client-credential", "")))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("SERVICE_AUTH_ISSUER_URI");
    }

    @Test
    void effectiveTokenUriIsDerivedFromTheIssuerUnlessOverridden() {
        assertThat(new Auth(Mode.NONE, "", "", "", "", "http://kc/realms/securebank", "").effectiveTokenUri())
                .isEqualTo("http://kc/realms/securebank/protocol/openid-connect/token");
        assertThat(new Auth(Mode.NONE, "", "", "", "http://kc/token", "http://kc/realms/securebank", "")
                .effectiveTokenUri()).isEqualTo("http://kc/token");
        assertThat(new Auth(Mode.NONE, "", "", "", "", "", "").effectiveTokenUri()).isEmpty();
    }
}
