package com.securebank.account.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

/** The service credential cannot be silently dropped outside tests, and a half-configured client fails fast. */
class CustomerClientConfigTest {

    private final CustomerClientConfig config = new CustomerClientConfig();

    private static AccountProperties props(AccountProperties.CustomerService.Auth.Mode mode, String clientId,
                                           String secret, String issuer) {
        return new AccountProperties(
                new AccountProperties.CustomerService("http://customer.test",
                        "/internal/v1/customers/{customerId}/eligibility", Duration.ofSeconds(2),
                        Duration.ofSeconds(3), List.of("ACTIVE"),
                        new AccountProperties.CustomerService.Auth(mode, "", clientId, secret, "", issuer, "")),
                new AccountProperties.Security("http://issuer.test/realms/securebank", "", "customer_id", "x"),
                new AccountProperties.Limits(5));
    }

    private static MockEnvironment profile(String name) {
        MockEnvironment env = new MockEnvironment();
        env.setActiveProfiles(name);
        return env;
    }

    @Test
    void noCredentialModeIsRefusedOutsideTestAndLocalProfiles() {
        AccountProperties none = props(AccountProperties.CustomerService.Auth.Mode.NONE, "", "", "");

        assertThatThrownBy(() -> config.customerServiceTokenProvider(none, new MockEnvironment()))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("OAUTH2_CLIENT_CREDENTIALS");
        assertThatThrownBy(() -> config.customerServiceTokenProvider(none, profile("prod")))
                .isInstanceOf(IllegalStateException.class);
        assertThat(config.customerServiceTokenProvider(none, profile("test")).bearerToken()).isEmpty();
        assertThat(config.customerServiceTokenProvider(none, profile("local")).bearerToken()).isEmpty();
    }

    @Test
    void clientCredentialsModeFailsFastWithoutASecretOrIssuerAndNeverEchoesOne() {
        String secret = "sentinel-secret-value";
        String client = "securebank-account-service";
        String issuer = "http://kc.test/realms/securebank";

        assertThatThrownBy(() -> config.customerServiceTokenProvider(
                props(AccountProperties.CustomerService.Auth.Mode.OAUTH2_CLIENT_CREDENTIALS, client, "", issuer),
                new MockEnvironment())).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("ACCOUNT_SERVICE_AUTH_CLIENT_SECRET");
        assertThatThrownBy(() -> config.customerServiceTokenProvider(
                props(AccountProperties.CustomerService.Auth.Mode.OAUTH2_CLIENT_CREDENTIALS, client, secret, ""),
                new MockEnvironment())).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("ACCOUNT_SERVICE_AUTH_ISSUER_URI").hasMessageNotContaining(secret);
        assertThat(config.customerServiceTokenProvider(
                props(AccountProperties.CustomerService.Auth.Mode.OAUTH2_CLIENT_CREDENTIALS, client, secret, issuer),
                new MockEnvironment())).isNotNull();
    }
}
