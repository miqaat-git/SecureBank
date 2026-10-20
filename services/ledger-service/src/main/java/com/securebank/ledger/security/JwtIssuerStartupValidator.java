package com.securebank.ledger.security;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.Locale;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

/**
 * Fails startup with a clear message when the configured JWT issuer is malformed. A trailing slash or a stray
 * path would otherwise make every token fail with an issuer mismatch at the first request. The check does not
 * contact the identity provider, so the service can still start before it is running.
 */
@Component
public class JwtIssuerStartupValidator implements InitializingBean {

    static final String PROPERTY = "spring.security.oauth2.resourceserver.jwt.issuer-uri";

    private static final Logger log = LoggerFactory.getLogger(JwtIssuerStartupValidator.class);

    private final Environment environment;

    public JwtIssuerStartupValidator(Environment environment) {
        this.environment = environment;
    }

    @Override
    public void afterPropertiesSet() {
        validate(environment.getProperty(PROPERTY));
    }

    static void validate(String issuerUri) {
        if (issuerUri == null || issuerUri.isBlank()) {
            throw new IllegalStateException("LEDGER_JWT_ISSUER_URI must not be empty: it must equal the 'iss' "
                    + "claim of the tokens Ledger Service should accept.");
        }
        URI uri;
        try {
            uri = new URI(issuerUri.trim());
        } catch (URISyntaxException ex) {
            throw new IllegalStateException("LEDGER_JWT_ISSUER_URI is not a valid URI.");
        }
        String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
        if (!(scheme.equals("http") || scheme.equals("https")) || uri.getHost() == null) {
            throw new IllegalStateException("LEDGER_JWT_ISSUER_URI must be an absolute http(s) URL, "
                    + "for example http://localhost:8180/realms/securebank.");
        }
        if (uri.getRawQuery() != null || uri.getRawFragment() != null) {
            throw new IllegalStateException("LEDGER_JWT_ISSUER_URI must not contain a query or fragment.");
        }
        if (issuerUri.trim().endsWith("/")) {
            throw new IllegalStateException("LEDGER_JWT_ISSUER_URI must not end with '/': the issuer is compared "
                    + "to the token's 'iss' claim character for character.");
        }
        boolean local = "localhost".equalsIgnoreCase(uri.getHost()) || "127.0.0.1".equals(uri.getHost());
        if (scheme.equals("http") && !local) {
            log.warn("LEDGER_JWT_ISSUER_URI uses plain http for a non-local host; use https outside development.");
        }
    }
}
