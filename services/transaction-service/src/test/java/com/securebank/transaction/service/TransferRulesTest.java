package com.securebank.transaction.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.securebank.transaction.config.TransferProperties;
import com.securebank.transaction.exception.ApiException;
import java.math.BigDecimal;
import java.time.Duration;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.http.HttpStatus;

class TransferRulesTest {

    private static final UUID A = UUID.randomUUID();
    private static final UUID B = UUID.randomUUID();
    private static final String KEY = "key-12345678";

    private final TransferRules rules = new TransferRules(new TransferProperties(Set.of("INR"),
            new BigDecimal("1000000.00"), 3, Duration.ZERO, Duration.ofSeconds(1), Duration.ofMinutes(2),
            new TransferProperties.Recovery(false, 25)));

    private TransferCommand cmd(String amount, String currency) {
        return new TransferCommand(A, B, new BigDecimal(amount), currency);
    }

    @ParameterizedTest
    @ValueSource(strings = {"100", "100.5", "100.50", "0.01", "10.000", "1000000.00", "1E+2"})
    void validAmountsAreNormalizedToTwoDecimalsWithoutRounding(String amount) {
        BigDecimal normalized = rules.validate(KEY, cmd(amount, "INR"));
        assertThat(normalized.scale()).isEqualTo(2);
        assertThat(normalized).isEqualByComparingTo(new BigDecimal(amount));
    }

    @ParameterizedTest
    @ValueSource(strings = {"0", "0.00", "-1", "-0.01"})
    void nonPositiveAmountsAreRejected(String amount) {
        assertThatThrownBy(() -> rules.validate(KEY, cmd(amount, "INR")))
                .isInstanceOfSatisfying(ApiException.class, e -> {
                    assertThat(e.getCode()).isEqualTo("INVALID_AMOUNT");
                    assertThat(e.getStatus()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
                });
    }

    @ParameterizedTest
    @ValueSource(strings = {"10.001", "0.005", "99.999"})
    void amountsNeedingRoundingAreRejectedNotRounded(String amount) {
        assertThatThrownBy(() -> rules.validate(KEY, cmd(amount, "INR")))
                .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.getCode()).isEqualTo("INVALID_AMOUNT"));
    }

    @Test
    void amountAboveMaximumIsRejected() {
        assertThatThrownBy(() -> rules.validate(KEY, cmd("1000000.01", "INR")))
                .isInstanceOfSatisfying(ApiException.class,
                        e -> assertThat(e.getCode()).isEqualTo("AMOUNT_EXCEEDS_LIMIT"));
    }

    @Test
    void unsupportedCurrencyIsRejectedAndLowerCaseSupportedCurrencyAccepted() {
        assertThatThrownBy(() -> rules.validate(KEY, cmd("10", "USD")))
                .isInstanceOfSatisfying(ApiException.class,
                        e -> assertThat(e.getCode()).isEqualTo("UNSUPPORTED_CURRENCY"));
        assertThat(rules.validate(KEY, cmd("10", "inr"))).isEqualByComparingTo("10");
    }

    @Test
    void sameSourceAndDestinationAreRejected() {
        assertThatThrownBy(() -> rules.validate(KEY, new TransferCommand(A, A, BigDecimal.TEN, "INR")))
                .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.getCode()).isEqualTo("SAME_ACCOUNT"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"short", "has space in it", "bad/slash/key1", "xxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxx"})
    void malformedIdempotencyKeysAreRejected(String key) {
        assertThatThrownBy(() -> rules.validate(key, cmd("10", "INR")))
                .isInstanceOfSatisfying(ApiException.class, e -> {
                    assertThat(e.getCode()).isEqualTo("INVALID_IDEMPOTENCY_KEY");
                    assertThat(e.getStatus()).isEqualTo(HttpStatus.BAD_REQUEST);
                });
    }

    @Test
    void nullIdempotencyKeyIsRejected() {
        assertThatThrownBy(() -> rules.validate(null, cmd("10", "INR"))).isInstanceOf(ApiException.class);
    }
}
