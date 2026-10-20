package com.securebank.gateway.support;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Tiny in-JVM downstream service (JDK HttpServer). Echoes what it received as JSON.
 * Special paths: ending in /slow (sleeps 2s), /business-error (409), /unavailable (503).
 */
public final class MockDownstream {

    private final String name;
    private final HttpServer server;
    private final AtomicInteger requests = new AtomicInteger();
    private final AtomicInteger posts = new AtomicInteger();
    private volatile Map<String, String> lastHeaders = Map.of();

    MockDownstream(String name) {
        this.name = name;
        try {
            server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        server.createContext("/", this::handle);
        server.setExecutor(Executors.newCachedThreadPool(r -> {
            Thread t = new Thread(r, "mock-" + name);
            t.setDaemon(true);
            return t;
        }));
        server.start();
        Runtime.getRuntime().addShutdownHook(new Thread(() -> server.stop(0)));
    }

    public String baseUrl() {
        return "http://127.0.0.1:" + server.getAddress().getPort();
    }

    public int requestCount() {
        return requests.get();
    }

    public int postCount() {
        return posts.get();
    }

    /** Header as received by this downstream (name case-insensitive), or null. */
    public String lastHeader(String headerName) {
        return lastHeaders.get(headerName.toLowerCase());
    }

    void reset() {
        requests.set(0);
        posts.set(0);
        lastHeaders = Map.of();
    }

    private void handle(HttpExchange ex) throws IOException {
        requests.incrementAndGet();
        String method = ex.getRequestMethod();
        if ("POST".equals(method)) {
            posts.incrementAndGet();
        }
        Map<String, String> headers = new ConcurrentHashMap<>();
        ex.getRequestHeaders().forEach((k, v) -> {
            if (!v.isEmpty()) {
                headers.put(k.toLowerCase(), v.get(0));
            }
        });
        lastHeaders = headers;

        String body = new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
        String path = ex.getRequestURI().getRawPath();

        if (path.endsWith("/slow")) {
            try {
                Thread.sleep(2000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        if (path.endsWith("/business-error")) {
            respond(ex, 409, "{\"code\":\"INSUFFICIENT_FUNDS\"}");
        } else if (path.endsWith("/unavailable")) {
            respond(ex, 503, "{\"code\":\"DOWNSTREAM_DOWN\"}");
        } else {
            respond(ex, 200, "{\"service\":" + js(name)
                    + ",\"method\":" + js(method)
                    + ",\"path\":" + js(path)
                    + ",\"query\":" + js(ex.getRequestURI().getRawQuery())
                    + ",\"authorization\":" + js(headers.get("authorization"))
                    + ",\"correlationId\":" + js(headers.get("x-correlation-id"))
                    + ",\"body\":" + js(body) + "}");
        }
    }

    private static void respond(HttpExchange ex, int status, String json) throws IOException {
        byte[] bytes = json.getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().set("Content-Type", "application/json");
        ex.sendResponseHeaders(status, bytes.length);
        ex.getResponseBody().write(bytes);
        ex.close();
    }

    private static String js(String v) {
        return v == null ? "null" : "\"" + v.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
    }
}
