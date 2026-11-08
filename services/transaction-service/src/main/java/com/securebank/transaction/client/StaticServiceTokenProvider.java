package com.securebank.transaction.client;

import com.securebank.transaction.config.RemoteClientProperties;
import java.util.Optional;

public class StaticServiceTokenProvider implements ServiceTokenProvider {

    private final String token;

    public StaticServiceTokenProvider(RemoteClientProperties.Auth auth) {
        if (auth.mode() == RemoteClientProperties.Auth.Mode.STATIC_BEARER) {
            if (auth.staticToken() == null || auth.staticToken().isBlank()) {
                throw new IllegalStateException(
                        "securebank.clients.auth.mode=STATIC_BEARER requires SERVICE_AUTH_TOKEN to be set");
            }
            this.token = auth.staticToken();
        } else {
            this.token = null;
        }
    }

    @Override
    public Optional<String> bearerToken() {
        return Optional.ofNullable(token);
    }
}
