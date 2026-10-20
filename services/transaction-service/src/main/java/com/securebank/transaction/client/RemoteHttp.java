package com.securebank.transaction.client;

import java.net.ConnectException;
import java.net.NoRouteToHostException;
import java.net.UnknownHostException;
import java.nio.charset.StandardCharsets;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

/**
 * Executes one HTTP exchange and classifies transport failures. It never throws for transport errors and never
 * interprets status codes: that is the job of {@link RemoteOutcomeMapper}.
 */
public class RemoteHttp {

    private static final int MAX_BODY_BYTES = 64 * 1024;

    private final RestClient client;
    private final ServiceTokenProvider tokens;

    public RemoteHttp(RestClient client, ServiceTokenProvider tokens) {
        this.client = client;
        this.tokens = tokens;
    }

    public HttpResult execute(HttpMethod method, String uriTemplate, Object[] uriVariables, Object body,
                              String idempotencyKey) {
        try {
            RestClient.RequestBodySpec spec = client.method(method)
                    .uri(uriTemplate, uriVariables)
                    .accept(MediaType.APPLICATION_JSON)
                    .headers(h -> {
                        tokens.bearerToken().ifPresent(h::setBearerAuth);
                        if (idempotencyKey != null) {
                            h.set("Idempotency-Key", idempotencyKey);
                        }
                    });
            if (body != null) {
                spec = spec.contentType(MediaType.APPLICATION_JSON).body(body);
            }
            return spec.exchange((request, response) -> {
                byte[] bytes = response.getBody().readNBytes(MAX_BODY_BYTES);
                return HttpResult.response(response.getStatusCode().value(),
                        new String(bytes, StandardCharsets.UTF_8));
            });
        } catch (RestClientException e) {
            return neverSent(e) ? HttpResult.notSent() : HttpResult.ambiguous();
        } catch (RuntimeException e) {
            return HttpResult.ambiguous();
        }
    }

    private static boolean neverSent(Throwable e) {
        for (Throwable t = e; t != null; t = t.getCause()) {
            if (t instanceof ConnectException || t instanceof UnknownHostException
                    || t instanceof NoRouteToHostException) {
                return true;
            }
            if (t.getCause() == t) {
                break;
            }
        }
        return false;
    }
}
