package com.securebank.gateway.ratelimit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;

class ClientAddressResolverTest {

    private static ServerHttpRequest request(String peer, String... forwardedFor) throws Exception {
        MockServerHttpRequest.BaseBuilder<?> builder = MockServerHttpRequest.get("/api/accounts");
        if (peer != null) {
            builder.remoteAddress(new InetSocketAddress(InetAddress.getByName(peer), 40000));
        }
        for (String value : forwardedFor) {
            builder.header("X-Forwarded-For", value);
        }
        return builder.build();
    }

    @Test
    void withoutTrustedProxiesTheForwardedHeaderIsNeverTrusted() throws Exception {
        ClientAddressResolver resolver = new ClientAddressResolver(List.of());
        assertThat(resolver.resolve(request("203.0.113.9", "198.51.100.1"))).isEqualTo("203.0.113.9");
        assertThat(resolver.resolve(request("203.0.113.9", "1.1.1.1, 2.2.2.2"))).isEqualTo("203.0.113.9");
    }

    @Test
    void anUntrustedPeerCannotChooseItsIdentityEvenIfProxiesAreConfigured() throws Exception {
        ClientAddressResolver resolver = new ClientAddressResolver(List.of("10.0.0.0/8"));
        assertThat(resolver.resolve(request("203.0.113.9", "198.51.100.1"))).isEqualTo("203.0.113.9");
    }

    @Test
    void aTrustedProxyContributesTheRightmostUntrustedHop() throws Exception {
        ClientAddressResolver resolver = new ClientAddressResolver(List.of("10.0.0.0/8", "192.168.1.5"));
        assertThat(resolver.resolve(request("10.1.2.3", "198.51.100.7"))).isEqualTo("198.51.100.7");
        // a client-supplied left entry is ignored: the rightmost non-proxy hop is the real client
        assertThat(resolver.resolve(request("10.1.2.3", "6.6.6.6, 198.51.100.7, 10.9.9.9")))
                .isEqualTo("198.51.100.7");
        assertThat(resolver.resolve(request("10.1.2.3", "198.51.100.7", "192.168.1.5")))
                .isEqualTo("198.51.100.7");
        assertThat(resolver.resolve(request("10.1.2.3"))).isEqualTo("10.1.2.3");
    }

    @Test
    void malformedForwardedChainsFallBackToThePeerAndHostnamesAreNeverResolved() throws Exception {
        ClientAddressResolver resolver = new ClientAddressResolver(List.of("10.0.0.0/8"));
        assertThat(resolver.resolve(request("10.1.2.3", "not-an-ip"))).isEqualTo("10.1.2.3");
        assertThat(resolver.resolve(request("10.1.2.3", "evil.example.com, 198.51.100.7"))).isEqualTo("10.1.2.3");
        assertThat(ClientAddressResolver.parseLiteral("localhost")).isNull();
        assertThat(ClientAddressResolver.parseLiteral("999.1.1.1")).isNull();
    }

    @Test
    void ipv6AndMissingPeerAreHandled() throws Exception {
        ClientAddressResolver resolver = new ClientAddressResolver(List.of("fd00::/8"));
        assertThat(resolver.resolve(request("fd00::1", "2001:db8::7"))).isEqualTo("2001:db8:0:0:0:0:0:7");
        assertThat(resolver.resolve(request(null))).isEqualTo(ClientAddressResolver.UNKNOWN);
    }

    @Test
    void invalidTrustedProxyConfigurationFailsFast() {
        assertThatThrownBy(() -> new ClientAddressResolver(List.of("proxy.internal"))).isInstanceOf(
                IllegalArgumentException.class);
        assertThatThrownBy(() -> new ClientAddressResolver(List.of("10.0.0.0/40"))).isInstanceOf(
                IllegalArgumentException.class);
    }
}
