package com.securebank.ledger.security;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class JwtIssuerStartupValidatorTest {

    @ParameterizedTest
    @ValueSource(strings = {
            "http://localhost:8180/realms/securebank",
            "https://id.example.org/realms/securebank"})
    void acceptsWellFormedIssuers(String issuer) {
        assertThatCode(() -> JwtIssuerStartupValidator.validate(issuer)).doesNotThrowAnyException();
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "", "   ", "localhost:8180/realms/securebank", "ftp://host/realms/x",
            "http://localhost:8180/realms/securebank/", "https://host/realms/x?a=b", "https://host/realms/x#frag"})
    void rejectsMalformedIssuers(String issuer) {
        assertThatThrownBy(() -> JwtIssuerStartupValidator.validate(issuer))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("LEDGER_JWT_ISSUER_URI");
    }

    @Test
    void rejectsMissingIssuer() {
        assertThatThrownBy(() -> JwtIssuerStartupValidator.validate(null))
                .isInstanceOf(IllegalStateException.class);
    }
}
