package com.securebank.account.service;

import java.security.SecureRandom;
import org.springframework.stereotype.Component;

/**
 * Generates 13-digit account numbers: 12 random digits (first digit 1-9) from a {@link SecureRandom} followed by a
 * Luhn check digit that catches typing errors. The number is random, so it reveals nothing about the account's
 * internal UUID, the customer, or the creation order. Candidates are checked against the database and protected
 * by the {@code uq_accounts_account_number} unique constraint.
 */
@Component
public class SecureAccountNumberGenerator implements AccountNumberGenerator {

    public static final int LENGTH = 13;

    private final SecureRandom random;

    public SecureAccountNumberGenerator() {
        this(new SecureRandom());
    }

    SecureAccountNumberGenerator(SecureRandom random) {
        this.random = random;
    }

    @Override
    public String generate() {
        StringBuilder digits = new StringBuilder(LENGTH);
        digits.append(1 + random.nextInt(9));
        for (int i = 1; i < LENGTH - 1; i++) {
            digits.append(random.nextInt(10));
        }
        digits.append(luhnCheckDigit(digits));
        return digits.toString();
    }

    /** Luhn check digit for the given payload (the digits without the check digit). */
    static int luhnCheckDigit(CharSequence payload) {
        int sum = 0;
        boolean doubleIt = true;
        for (int i = payload.length() - 1; i >= 0; i--) {
            int digit = payload.charAt(i) - '0';
            if (doubleIt) {
                digit *= 2;
                if (digit > 9) {
                    digit -= 9;
                }
            }
            sum += digit;
            doubleIt = !doubleIt;
        }
        return (10 - (sum % 10)) % 10;
    }

    /** True for a 13-digit string whose Luhn check digit is correct. */
    public static boolean isValid(String accountNumber) {
        if (accountNumber == null || !accountNumber.matches("\\d{" + LENGTH + "}")) {
            return false;
        }
        String payload = accountNumber.substring(0, LENGTH - 1);
        return luhnCheckDigit(payload) == accountNumber.charAt(LENGTH - 1) - '0';
    }
}
