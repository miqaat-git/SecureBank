package com.securebank.gateway.ratelimit;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.UnknownHostException;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;
import org.springframework.http.server.reactive.ServerHttpRequest;

/**
 * Determines the client address used as the anonymous rate-limit key.
 *
 * <p>The TCP peer address is always the starting point. {@code X-Forwarded-For} is consulted ONLY when the peer is
 * one of the configured trusted proxies, and then it is read from the right, skipping trusted proxies; the first
 * address that is not a trusted proxy is the client. A malformed header value, or a header from an untrusted peer, is
 * ignored. With no trusted proxies configured (the default) the header is never trusted, so a caller cannot choose
 * its own rate-limit identity.
 */
public class ClientAddressResolver {

    static final String UNKNOWN = "unknown";
    private static final Pattern IPV4 = Pattern.compile("(\\d{1,3}\\.){3}\\d{1,3}");
    private static final Pattern IPV6 = Pattern.compile("[0-9a-fA-F:.]+");

    private record Cidr(byte[] network, int prefixBits) {
        boolean contains(byte[] address) {
            if (address.length != network.length) {
                return false;
            }
            int fullBytes = prefixBits / 8;
            for (int i = 0; i < fullBytes; i++) {
                if (address[i] != network[i]) {
                    return false;
                }
            }
            int rest = prefixBits % 8;
            if (rest == 0) {
                return true;
            }
            int mask = (0xFF << (8 - rest)) & 0xFF;
            return (address[fullBytes] & mask) == (network[fullBytes] & mask);
        }
    }

    private final List<Cidr> trusted = new ArrayList<>();

    public ClientAddressResolver(List<String> trustedProxies) {
        for (String entry : trustedProxies) {
            trusted.add(parseCidr(entry.trim()));
        }
    }

    public String resolve(ServerHttpRequest request) {
        InetSocketAddress remote = request.getRemoteAddress();
        if (remote == null || remote.getAddress() == null) {
            return UNKNOWN;
        }
        String peer = remote.getAddress().getHostAddress();
        if (!isTrusted(remote.getAddress().getAddress())) {
            return peer;
        }
        List<String> headers = request.getHeaders().get("X-Forwarded-For");
        if (headers == null || headers.isEmpty()) {
            return peer;
        }
        List<String> hops = new ArrayList<>();
        for (String header : headers) {
            for (String hop : header.split(",", -1)) {
                hops.add(hop.trim());
            }
        }
        // Validate the complete chain before selecting a client address.
        // A valid rightmost IP must not hide a malformed earlier entry.
        for (String hop : hops) {
            if (parseLiteral(hop) == null) {
                return peer;
            }
        }
        for (int i = hops.size() - 1; i >= 0; i--) {
            InetAddress address = parseLiteral(hops.get(i));
            if (address == null) {
                return peer;                      // malformed chain: do not trust any of it
            }
            if (!isTrusted(address.getAddress())) {
                return address.getHostAddress();
            }
        }
        return peer;
    }

    private boolean isTrusted(byte[] address) {
        return trusted.stream().anyMatch(c -> c.contains(address));
    }

    private static Cidr parseCidr(String entry) {
        String[] parts = entry.split("/", -1);
        InetAddress address = parseLiteral(parts[0]);
        if (address == null || parts.length > 2) {
            throw new IllegalArgumentException("Invalid trusted proxy entry (IP or CIDR expected)");
        }
        int max = address.getAddress().length * 8;
        int bits = max;
        if (parts.length == 2) {
            try {
                bits = Integer.parseInt(parts[1]);
            } catch (NumberFormatException e) {
                throw new IllegalArgumentException("Invalid trusted proxy prefix length");
            }
            if (bits < 0 || bits > max) {
                throw new IllegalArgumentException("Invalid trusted proxy prefix length");
            }
        }
        return new Cidr(address.getAddress(), bits);
    }

    /** Parses an IP literal only; never performs a DNS lookup. */
    static InetAddress parseLiteral(String value) {
        if (value == null || value.isEmpty() || value.length() > 45) {
            return null;
        }
        boolean ipv4 = IPV4.matcher(value).matches();
        boolean ipv6 = value.indexOf(':') >= 0 && IPV6.matcher(value).matches();
        if (!ipv4 && !ipv6) {
            return null;
        }
        try {
            return InetAddress.getByName(value);
        } catch (UnknownHostException e) {
            return null;
        }
    }
}
