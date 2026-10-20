package com.securebank.transaction.client;

import java.time.Duration;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

/** Builds RestClient instances with finite connect and read timeouts. */
public final class RestClients {

    private RestClients() {
    }

    public static RestClient create(String baseUrl, Duration connectTimeout, Duration readTimeout) {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout((int) connectTimeout.toMillis());
        factory.setReadTimeout((int) readTimeout.toMillis());
        return RestClient.builder().baseUrl(baseUrl).requestFactory(factory).build();
    }
}
