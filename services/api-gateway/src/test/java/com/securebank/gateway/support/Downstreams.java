package com.securebank.gateway.support;

import org.springframework.test.context.DynamicPropertyRegistry;

/** Shared mock downstream services, started once per JVM on random local ports. */
public final class Downstreams {

    public static final MockDownstream CUSTOMER = new MockDownstream("customer");
    public static final MockDownstream ACCOUNT = new MockDownstream("account");
    public static final MockDownstream TRANSACTION = new MockDownstream("transaction");
    public static final MockDownstream LEDGER = new MockDownstream("ledger");

    private Downstreams() {
    }

    public static void register(DynamicPropertyRegistry registry) {
        registry.add("securebank.services.customer-base-url", CUSTOMER::baseUrl);
        registry.add("securebank.services.account-base-url", ACCOUNT::baseUrl);
        registry.add("securebank.services.transaction-base-url", TRANSACTION::baseUrl);
        registry.add("securebank.services.ledger-base-url", LEDGER::baseUrl);
    }

    public static void reset() {
        CUSTOMER.reset();
        ACCOUNT.reset();
        TRANSACTION.reset();
        LEDGER.reset();
    }

    public static MockDownstream byName(String name) {
        return switch (name) {
            case "customer" -> CUSTOMER;
            case "account" -> ACCOUNT;
            case "transaction" -> TRANSACTION;
            case "ledger" -> LEDGER;
            default -> throw new IllegalArgumentException(name);
        };
    }
}
