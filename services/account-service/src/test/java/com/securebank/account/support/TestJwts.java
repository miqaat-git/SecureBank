package com.securebank.account.support;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;

import java.util.UUID;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

/** Mock-JWT helpers for the three kinds of callers. */
public final class TestJwts {

    private TestJwts() {
    }

    public static RequestPostProcessor admin() {
        return jwt().jwt(token -> token.subject("admin-user-1"))
                .authorities(new SimpleGrantedAuthority("ROLE_ADMIN"));
    }

    public static RequestPostProcessor customer(UUID customerId) {
        return jwt().jwt(token -> token.subject("customer-user-1").claim("customer_id", customerId.toString()))
                .authorities(new SimpleGrantedAuthority("ROLE_CUSTOMER"));
    }

    /** A CUSTOMER token that carries no customer_id claim. */
    public static RequestPostProcessor customerWithoutCustomerId() {
        return jwt().jwt(token -> token.subject("customer-user-2"))
                .authorities(new SimpleGrantedAuthority("ROLE_CUSTOMER"));
    }

    public static RequestPostProcessor transactionService() {
        return jwt().jwt(token -> token.subject("transaction-service-account"))
                .authorities(new SimpleGrantedAuthority("ROLE_TRANSACTION_SERVICE"));
    }
}
