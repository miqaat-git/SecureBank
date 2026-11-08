package com.securebank.transaction.workflow;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.securebank.transaction.AbstractIntegrationTest;
import com.securebank.transaction.domain.Transfer;
import com.securebank.transaction.exception.ApiException;
import com.securebank.transaction.security.Caller;
import com.securebank.transaction.service.InitiationResult;
import com.securebank.transaction.service.TransferCommand;
import com.securebank.transaction.service.TransferQueryService;
import java.math.BigDecimal;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/** Idempotency keys are client-chosen, so they are scoped to the initiating customer. */
class IdempotencyKeyScopeTest extends AbstractIntegrationTest {

    private static final String KEY = "test-key-scope-shared";

    @Autowired TransferQueryService queries;

    private static Caller customer2() {
        return new Caller("user-2", "CUST-2", false);
    }

    private static Caller operator() {
        return new Caller("ops-1", null, true);
    }

    /** CUST-2 owns DST, so it initiates from DST. */
    private static TransferCommand cmd2() {
        return new TransferCommand(DST, SRC, new BigDecimal("3.00"), "INR");
    }

    @Test
    void anotherCustomerCanUseTheSameKeyAndGetsItsOwnTransfer() {
        stubEverythingSucceeds();

        Transfer first = transferService.initiate(KEY, cmd("5.00"), customer()).transfer();
        InitiationResult second = transferService.initiate(KEY, cmd2(), customer2());

        assertThat(second.replay()).isFalse();
        assertThat(second.transfer().getId()).isNotEqualTo(first.getId());
        assertThat(second.transfer().getInitiatorCustomerId()).isEqualTo("CUST-2");
        assertThat(transferRepo.count()).isEqualTo(2);
    }

    @Test
    void sameCustomerReplayStillReturnsTheOriginalAndNeverDuplicates() {
        stubEverythingSucceeds();

        Transfer first = transferService.initiate(KEY, cmd("5.00"), customer()).transfer();
        InitiationResult replay = transferService.initiate(KEY, cmd("5.00"), customer());

        assertThat(replay.replay()).isTrue();
        assertThat(replay.transfer().getId()).isEqualTo(first.getId());
        assertThat(transferRepo.count()).isEqualTo(1);
        assertThatThrownBy(() -> transferService.initiate(KEY, cmd("6.00"), customer()))
                .isInstanceOfSatisfying(ApiException.class,
                        e -> assertThat(e.getCode()).isEqualTo("IDEMPOTENCY_KEY_REUSED"));
    }

    @Test
    void customerLookupByKeyOnlyEverSeesItsOwnTransfer() {
        stubEverythingSucceeds();
        Transfer mine = transferService.initiate(KEY, cmd("5.00"), customer()).transfer();
        Transfer theirs = transferService.initiate(KEY, cmd2(), customer2()).transfer();

        assertThat(queries.getByKey(KEY, customer()).getId()).isEqualTo(mine.getId());
        assertThat(queries.getByKey(KEY, customer2()).getId()).isEqualTo(theirs.getId());
    }

    @Test
    void aKeyUsedOnlyByAnotherCustomerLooksNonExistent() {
        stubEverythingSucceeds();
        transferService.initiate(KEY, cmd("5.00"), customer()).transfer();

        assertThatThrownBy(() -> queries.getByKey(KEY, customer2()))
                .isInstanceOfSatisfying(ApiException.class,
                        e -> assertThat(e.getCode()).isEqualTo("TRANSFER_NOT_FOUND"));
        assertThatThrownBy(() -> queries.getByKey(KEY, new Caller("no-claim", null, false)))
                .isInstanceOfSatisfying(ApiException.class,
                        e -> assertThat(e.getCode()).isEqualTo("TRANSFER_NOT_FOUND"));
    }

    @Test
    void operatorLookupReturnsTheUniqueMatchAndRefusesAnAmbiguousKey() {
        stubEverythingSucceeds();
        Transfer mine = transferService.initiate(KEY, cmd("5.00"), customer()).transfer();

        assertThat(queries.getByKey(KEY, operator()).getId()).isEqualTo(mine.getId());

        transferService.initiate(KEY, cmd2(), customer2());
        assertThatThrownBy(() -> queries.getByKey(KEY, operator()))
                .isInstanceOfSatisfying(ApiException.class,
                        e -> assertThat(e.getCode()).isEqualTo("IDEMPOTENCY_KEY_AMBIGUOUS"));
        assertThatThrownBy(() -> queries.getByKey("test-key-scope-missing", operator()))
                .isInstanceOfSatisfying(ApiException.class,
                        e -> assertThat(e.getCode()).isEqualTo("TRANSFER_NOT_FOUND"));
    }

    @Test
    void keyReuseConflictIsReportedBeforeBusinessValidationCanMaskIt() {
        stubEverythingSucceeds();
        transferService.initiate(KEY, cmd("5.00"), customer());

        for (TransferCommand different : new TransferCommand[] {
                new TransferCommand(SRC, DST, new BigDecimal("5.00"), "USD"),         // unsupported currency
                new TransferCommand(SRC, SRC, new BigDecimal("5.00"), "INR"),         // same account
                new TransferCommand(SRC, DST, new BigDecimal("-1"), "INR"),           // invalid amount
                new TransferCommand(SRC, DST, new BigDecimal("5.001"), "INR")}) {     // too many decimals
            assertThatThrownBy(() -> transferService.initiate(KEY, different, customer()))
                    .isInstanceOfSatisfying(ApiException.class,
                            e -> assertThat(e.getCode()).isEqualTo("IDEMPOTENCY_KEY_REUSED"));
        }
        // equivalent spellings of the same request replay
        assertThat(transferService.initiate(KEY, new TransferCommand(SRC, DST, new BigDecimal("5"), "inr"),
                customer()).replay()).isTrue();
        assertThat(transferService.initiate(KEY, new TransferCommand(SRC, DST, new BigDecimal("5.0"), "INR"),
                customer()).replay()).isTrue();
        assertThat(transferRepo.count()).isEqualTo(1);
    }

    @Test
    void newRequestsStillGetNormalValidationErrors() {
        stubEverythingSucceeds();

        assertThatThrownBy(() -> transferService.initiate("test-key-scope-new-1",
                new TransferCommand(SRC, DST, new BigDecimal("5.00"), "USD"), customer()))
                .isInstanceOfSatisfying(ApiException.class,
                        e -> assertThat(e.getCode()).isEqualTo("UNSUPPORTED_CURRENCY"));
        assertThatThrownBy(() -> transferService.initiate("test-key-scope-new-2",
                new TransferCommand(SRC, DST, null, "INR"), customer()))
                .isInstanceOfSatisfying(ApiException.class,
                        e -> assertThat(e.getCode()).isEqualTo("VALIDATION_FAILED"));
        assertThatThrownBy(() -> transferService.initiate(KEY, new TransferCommand(SRC, DST, null, null), customer()))
                .isInstanceOfSatisfying(ApiException.class,
                        e -> assertThat(e.getCode()).isEqualTo("VALIDATION_FAILED"));
        assertThat(transferRepo.count()).isZero();
    }

    @Test
    void anotherCustomersUnsupportedCurrencyRequestDoesNotRevealTheExistingKey() {
        stubEverythingSucceeds();
        transferService.initiate(KEY, cmd("5.00"), customer());

        assertThatThrownBy(() -> transferService.initiate(KEY,
                new TransferCommand(DST, SRC, new BigDecimal("3.00"), "USD"), customer2()))
                .isInstanceOfSatisfying(ApiException.class,
                        e -> assertThat(e.getCode()).isEqualTo("UNSUPPORTED_CURRENCY"));
    }
}
