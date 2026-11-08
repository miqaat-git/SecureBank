package com.securebank.customer.service;

/** The authenticated caller of an administrative operation, taken from the validated token only. */
public record StatusActor(String subject, boolean admin) {

    public String role() {
        return admin ? "ADMIN" : "STAFF";
    }
}
