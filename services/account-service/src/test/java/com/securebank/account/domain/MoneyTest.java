package com.securebank.account.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.securebank.account.exception.ApiException;
import com.securebank.account.exception.ErrorCode;
import java.math.BigDecimal;
import org.junit.jupiter.api.Test;

class MoneyTest {

    @Test
    void normalizesToScaleTwoWithoutChangingTheValue() {
        BigDecimal result = Money.normalizePositive(new BigDecimal("10.5"));
        assertThat(result).isEqualByComparingTo("10.50");
        assertThat(result.scale()).isEqualTo(2);
        assertThat(Money.normalizePositive(new BigDecimal("100")).scale()).isEqualTo(2);
    }

    @Test
    void trailingZerosBeyondTwoDecimalsAreAccepted() {
        assertThat(Money.normalizePositive(new BigDecimal("1.500"))).isEqualByComparingTo("1.50");
    }

    @Test
    void rejectsMoreThanTwoSignificantDecimalsInsteadOfRounding() {
        assertThatThrownBy(() -> Money.normalizePositive(new BigDecimal("1.005")))
                .isInstanceOfSatisfying(ApiException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo(ErrorCode.INVALID_AMOUNT));
    }

    @Test
    void rejectsNullZeroAndNegativeAmounts() {
        assertThatThrownBy(() -> Money.normalizePositive(null)).isInstanceOf(ApiException.class);
        assertThatThrownBy(() -> Money.normalizePositive(BigDecimal.ZERO)).isInstanceOf(ApiException.class);
        assertThatThrownBy(() -> Money.normalizePositive(new BigDecimal("-0.01"))).isInstanceOf(ApiException.class);
    }

    @Test
    void rejectsAmountsAboveTheMaximumAndAbsurdExponents() {
        assertThat(Money.normalizePositive(Money.MAX_AMOUNT)).isEqualByComparingTo(Money.MAX_AMOUNT);
        assertThatThrownBy(() -> Money.normalizePositive(new BigDecimal("100000000000000000.00")))
                .isInstanceOf(ApiException.class);
        assertThatThrownBy(() -> Money.normalizePositive(new BigDecimal("1E+400")))
                .isInstanceOf(ApiException.class);
    }
}
