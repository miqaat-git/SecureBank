package com.securebank.account.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.securebank.account.exception.ApiException;
import com.securebank.account.exception.ErrorCode;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** Pure domain tests: no Spring, no database. */
class AccountTest {

    private static final Instant T0 = Instant.parse("2026-01-01T00:00:00Z");
    private static final Instant T1 = Instant.parse("2026-01-02T00:00:00Z");

    private Account newAccount() {
        return Account.open(UUID.randomUUID(), "1234567890123", AccountType.SAVINGS, "INR", T0);
    }

    private static void assertCode(Runnable action, ErrorCode expected) {
        assertThatThrownBy(action::run).isInstanceOfSatisfying(ApiException.class,
                e -> assertThat(e.getErrorCode()).isEqualTo(expected));
    }

    @Test
    void opensActiveWithZeroBalanceAtScaleTwo() {
        Account account = newAccount();
        assertThat(account.getStatus()).isEqualTo(AccountStatus.ACTIVE);
        assertThat(account.getAvailableBalance()).isEqualByComparingTo("0");
        assertThat(account.getAvailableBalance().scale()).isEqualTo(2);
        assertThat(account.getId()).isNotNull();
        assertThat(account.getCreatedAt()).isEqualTo(T0);
        assertThat(account.getVersion()).isNull(); // assigned by JPA on first persist
    }

    @Test
    void repeatedSmallCreditsAreExact() {
        Account account = newAccount();
        for (int i = 0; i < 10; i++) {
            account.credit(new BigDecimal("0.10"), T1);
        }
        assertThat(account.getAvailableBalance()).isEqualByComparingTo("1.00");
        assertThat(account.getUpdatedAt()).isEqualTo(T1);
    }

    @Test
    void debitReducesBalanceAndNeverGoesNegative() {
        Account account = newAccount();
        account.credit(new BigDecimal("100.00"), T1);
        account.debit(new BigDecimal("40.25"), T1);
        assertThat(account.getAvailableBalance()).isEqualByComparingTo("59.75");

        assertCode(() -> account.debit(new BigDecimal("59.76"), T1), ErrorCode.INSUFFICIENT_FUNDS);
        assertThat(account.getAvailableBalance()).isEqualByComparingTo("59.75");

        account.debit(new BigDecimal("59.75"), T1); // exactly the balance is allowed
        assertThat(account.getAvailableBalance()).isEqualByComparingTo("0.00");
    }

    @Test
    void creditCannotExceedTheMaximumBalance() {
        Account account = newAccount();
        account.credit(Money.MAX_AMOUNT, T1);
        assertCode(() -> account.credit(new BigDecimal("0.01"), T1), ErrorCode.BALANCE_LIMIT_EXCEEDED);
        assertThat(account.getAvailableBalance()).isEqualByComparingTo(Money.MAX_AMOUNT);
    }

    @Test
    void invalidAmountsAreRejectedAndLeaveTheBalanceUntouched() {
        Account account = newAccount();
        account.credit(new BigDecimal("10.00"), T1);
        assertCode(() -> account.credit(new BigDecimal("-1"), T1), ErrorCode.INVALID_AMOUNT);
        assertCode(() -> account.debit(BigDecimal.ZERO, T1), ErrorCode.INVALID_AMOUNT);
        assertCode(() -> account.credit(new BigDecimal("0.001"), T1), ErrorCode.INVALID_AMOUNT);
        assertThat(account.getAvailableBalance()).isEqualByComparingTo("10.00");
    }

    @Test
    void frozenAccountRejectsCreditsAndDebits() {
        Account account = newAccount();
        account.credit(new BigDecimal("10.00"), T1);
        account.freeze("fraud review", "admin-1", T1);

        assertThat(account.getStatus()).isEqualTo(AccountStatus.FROZEN);
        assertCode(() -> account.credit(new BigDecimal("1.00"), T1), ErrorCode.ACCOUNT_NOT_OPERATIONAL);
        assertCode(() -> account.debit(new BigDecimal("1.00"), T1), ErrorCode.ACCOUNT_NOT_OPERATIONAL);
        assertThat(account.getAvailableBalance()).isEqualByComparingTo("10.00");
    }

    @Test
    void freezeAndUnfreezeFollowTheStateMachine() {
        Account account = newAccount();
        assertCode(() -> account.unfreeze("x", "admin-1", T1), ErrorCode.INVALID_ACCOUNT_STATE);

        account.freeze("review", "admin-1", T1);
        assertThat(account.getStatusReason()).isEqualTo("review");
        assertThat(account.getStatusChangedBy()).isEqualTo("admin-1");
        assertThat(account.getStatusChangedAt()).isEqualTo(T1);
        assertCode(() -> account.freeze("again", "admin-1", T1), ErrorCode.INVALID_ACCOUNT_STATE);

        account.unfreeze("cleared", "admin-2", T1);
        assertThat(account.getStatus()).isEqualTo(AccountStatus.ACTIVE);
        account.credit(new BigDecimal("1.00"), T1); // operational again
    }

    @Test
    void closeRequiresZeroBalance() {
        Account account = newAccount();
        account.credit(new BigDecimal("0.01"), T1);
        assertCode(() -> account.close("owner", T1), ErrorCode.ACCOUNT_CLOSURE_NOT_ALLOWED);
        assertThat(account.getStatus()).isEqualTo(AccountStatus.ACTIVE);
    }

    @Test
    void frozenAccountCannotBeClosed() {
        Account account = newAccount();
        account.freeze("review", "admin-1", T1);
        assertCode(() -> account.close("owner", T1), ErrorCode.ACCOUNT_CLOSURE_NOT_ALLOWED);
    }

    @Test
    void closeIsTerminal() {
        Account account = newAccount();
        account.close("owner", T1);

        assertThat(account.getStatus()).isEqualTo(AccountStatus.CLOSED);
        assertThat(account.getClosedAt()).isEqualTo(T1);
        assertCode(() -> account.close("owner", T1), ErrorCode.INVALID_ACCOUNT_STATE);
        assertCode(() -> account.freeze("x", "admin-1", T1), ErrorCode.INVALID_ACCOUNT_STATE);
        assertCode(() -> account.unfreeze("x", "admin-1", T1), ErrorCode.INVALID_ACCOUNT_STATE);
        assertCode(() -> account.credit(new BigDecimal("1.00"), T1), ErrorCode.ACCOUNT_NOT_OPERATIONAL);
    }
}
