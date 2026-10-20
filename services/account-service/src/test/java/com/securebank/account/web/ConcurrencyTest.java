package com.securebank.account.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.securebank.account.domain.Account;
import com.securebank.account.domain.BalanceOperationType;
import com.securebank.account.dto.BalanceOperationRequest;
import com.securebank.account.dto.BalanceOperationResponse;
import com.securebank.account.exception.ApiException;
import com.securebank.account.exception.ErrorCode;
import com.securebank.account.service.BalanceOperationService;
import com.securebank.account.support.AbstractIntegrationTest;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.orm.ObjectOptimisticLockingFailureException;

/** Concurrency control: optimistic locking and idempotency under parallel callers. */
class ConcurrencyTest extends AbstractIntegrationTest {

    @Autowired
    private BalanceOperationService balanceOperationService;

    @Test
    void staleWriterIsRejectedInsteadOfOverwritingTheNewerBalance() {
        Account saved = insertAccount(UUID.randomUUID());
        Account first = accountRepository.findById(saved.getId()).orElseThrow();
        Account second = accountRepository.findById(saved.getId()).orElseThrow();

        first.credit(new BigDecimal("10.00"), Instant.now());
        accountRepository.saveAndFlush(first);

        second.credit(new BigDecimal("5.00"), Instant.now());
        assertThatThrownBy(() -> accountRepository.saveAndFlush(second))
                .isInstanceOf(ObjectOptimisticLockingFailureException.class);

        assertThat(accountRepository.findById(saved.getId()).orElseThrow().getAvailableBalance())
                .isEqualByComparingTo("10.00");
    }

    @Test
    void parallelCreditsNeverLoseAnUpdate() throws Exception {
        Account account = insertAccount(UUID.randomUUID());
        int threads = 6;
        int creditsPerThread = 5;
        AtomicInteger applied = new AtomicInteger();
        AtomicInteger conflicts = new AtomicInteger();

        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<Void>> futures = new ArrayList<>();
        for (int t = 0; t < threads; t++) {
            final int thread = t;
            Callable<Void> task = () -> {
                start.await();
                for (int i = 0; i < creditsPerThread; i++) {
                    BalanceOperationRequest request = new BalanceOperationRequest(BalanceOperationType.CREDIT,
                            new BigDecimal("1.00"), "key-par-" + thread + "-" + i + "-" + UUID.randomUUID(),
                            UUID.randomUUID());
                    try {
                        balanceOperationService.apply(account.getId(), request, "test");
                        applied.incrementAndGet();
                    } catch (ApiException ex) {
                        // The only acceptable failure is an explicit conflict - never a silent overwrite.
                        assertThat(ex.getErrorCode()).isEqualTo(ErrorCode.CONCURRENT_MODIFICATION);
                        conflicts.incrementAndGet();
                    }
                }
                return null;
            };
            futures.add(pool.submit(task));
        }
        start.countDown();
        for (Future<Void> future : futures) {
            future.get(60, TimeUnit.SECONDS);
        }
        pool.shutdown();

        assertThat(applied.get() + conflicts.get()).isEqualTo(threads * creditsPerThread);
        assertThat(applied.get()).isPositive();
        BigDecimal balance = accountRepository.findById(account.getId()).orElseThrow().getAvailableBalance();
        assertThat(balance).isEqualByComparingTo(new BigDecimal(applied.get()));
        assertThat(balanceOperationRepository.count()).isEqualTo(applied.get());
    }

    @Test
    void parallelRequestsWithTheSameKeyApplyExactlyOnce() throws Exception {
        Account account = insertAccount(UUID.randomUUID());
        int threads = 6;
        UUID reference = UUID.randomUUID();
        BalanceOperationRequest request = new BalanceOperationRequest(BalanceOperationType.CREDIT,
                new BigDecimal("10.00"), "key-same-" + UUID.randomUUID(), reference);

        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<BalanceOperationResponse>> futures = new ArrayList<>();
        for (int t = 0; t < threads; t++) {
            futures.add(pool.submit(() -> {
                start.await();
                return balanceOperationService.apply(account.getId(), request, "test");
            }));
        }
        start.countDown();
        int notReplayed = 0;
        for (Future<BalanceOperationResponse> future : futures) {
            if (!future.get(60, TimeUnit.SECONDS).replayed()) {
                notReplayed++;
            }
        }
        pool.shutdown();

        assertThat(notReplayed).isEqualTo(1);
        assertThat(balanceOperationRepository.count()).isEqualTo(1);
        assertThat(accountRepository.findById(account.getId()).orElseThrow().getAvailableBalance())
                .isEqualByComparingTo("10.00");
    }
}
