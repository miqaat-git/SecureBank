package com.securebank.transaction.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.securebank.transaction.client.AccountServiceClient;
import com.securebank.transaction.client.AccountServiceHttpClient;
import com.securebank.transaction.client.LedgerServiceClient;
import com.securebank.transaction.client.LedgerServiceHttpClient;
import com.securebank.transaction.client.OAuth2ClientCredentialsTokenProvider;
import com.securebank.transaction.client.RemoteHttp;
import com.securebank.transaction.client.RestClients;
import com.securebank.transaction.client.ServiceTokenProvider;
import com.securebank.transaction.client.StaticServiceTokenProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class RemoteClientsConfig {

    @Bean
    public ServiceTokenProvider serviceTokenProvider(RemoteClientProperties props) {
        if (props.auth().mode() == RemoteClientProperties.Auth.Mode.OAUTH2_CLIENT_CREDENTIALS) {
            return OAuth2ClientCredentialsTokenProvider.create(props.auth());
        }
        return new StaticServiceTokenProvider(props.auth());
    }

    @Bean
    public AccountServiceClient accountServiceClient(RemoteClientProperties props, ServiceTokenProvider tokens,
                                                     ObjectMapper objectMapper) {
        RemoteClientProperties.Account cfg = props.account();
        RemoteHttp http = new RemoteHttp(
                RestClients.create(cfg.baseUrl(), cfg.connectTimeout(), cfg.readTimeout()), tokens);
        return new AccountServiceHttpClient(http, cfg, objectMapper);
    }

    @Bean
    public LedgerServiceClient ledgerServiceClient(RemoteClientProperties props, ServiceTokenProvider tokens,
                                                   ObjectMapper objectMapper) {
        RemoteClientProperties.Ledger cfg = props.ledger();
        RemoteHttp http = new RemoteHttp(
                RestClients.create(cfg.baseUrl(), cfg.connectTimeout(), cfg.readTimeout()), tokens);
        return new LedgerServiceHttpClient(http, cfg, objectMapper);
    }
}
