package com.securebank.account.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

/** Builds the RestClient used to call Customer Service, with explicit connect and read timeouts. */
@Configuration
public class CustomerClientConfig {

    @Bean
    public RestClient customerServiceRestClient(AccountProperties properties) {
        AccountProperties.CustomerService config = properties.customerService();
        SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
        requestFactory.setConnectTimeout((int) config.connectTimeout().toMillis());
        requestFactory.setReadTimeout((int) config.readTimeout().toMillis());
        return RestClient.builder()
                .baseUrl(config.baseUrl())
                .requestFactory(requestFactory)
                .build();
    }
}
