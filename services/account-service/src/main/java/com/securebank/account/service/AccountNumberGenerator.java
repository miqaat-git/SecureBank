package com.securebank.account.service;

/** Produces candidate account numbers. Uniqueness is guaranteed by the database, not by the generator. */
public interface AccountNumberGenerator {

    String generate();
}
