package com.securebank.gateway.config;

import io.netty.channel.ChannelOption;
import org.springframework.cloud.gateway.config.HttpClientCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Applies connect and response timeouts to the Netty client used for every downstream call. */
@Configuration(proxyBeanMethods = false)
public class GatewayHttpClientConfig {

    @Bean
    HttpClientCustomizer secureBankHttpClientTimeouts(SecureBankProperties props) {
        int connectMillis = Math.toIntExact(props.timeouts().connect().toMillis());
        return httpClient -> httpClient
                .option(ChannelOption.CONNECT_TIMEOUT_MILLIS, connectMillis)
                .responseTimeout(props.timeouts().response());
    }
}
