package com.securebank.account.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.HashSet;
import java.util.Set;
import org.junit.jupiter.api.Test;

class SecureAccountNumberGeneratorTest {

    private final SecureAccountNumberGenerator generator = new SecureAccountNumberGenerator();

    @Test
    void generatesThirteenDigitNumbersWithoutLeadingZeroAndValidCheckDigit() {
        for (int i = 0; i < 1_000; i++) {
            String number = generator.generate();
            assertThat(number).matches("[1-9]\\d{12}");
            assertThat(SecureAccountNumberGenerator.isValid(number)).isTrue();
        }
    }

    @Test
    void generatedNumbersAreUniqueInPractice() {
        Set<String> seen = new HashSet<>();
        for (int i = 0; i < 5_000; i++) {
            assertThat(seen.add(generator.generate())).as("duplicate account number").isTrue();
        }
    }

    @Test
    void checkDigitDetectsASingleDigitTypo() {
        String number = generator.generate();
        char first = number.charAt(1);
        char changed = first == '9' ? '0' : (char) (first + 1);
        String typo = number.charAt(0) + String.valueOf(changed) + number.substring(2);
        assertThat(SecureAccountNumberGenerator.isValid(typo)).isFalse();
    }

    @Test
    void validatorRejectsMalformedValues() {
        assertThat(SecureAccountNumberGenerator.isValid(null)).isFalse();
        assertThat(SecureAccountNumberGenerator.isValid("123")).isFalse();
        assertThat(SecureAccountNumberGenerator.isValid("abcdefghijklm")).isFalse();
    }
}
