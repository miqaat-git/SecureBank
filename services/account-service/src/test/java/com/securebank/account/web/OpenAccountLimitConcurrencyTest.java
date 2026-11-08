package com.securebank.account.web;

import static org.assertj.core.api.Assertions.assertThat;

import com.securebank.account.domain.AccountStatus;
import com.securebank.account.domain.AccountType;
import com.securebank.account.dto.CreateAccountRequest;
import com.securebank.account.exception.ApiException;
import com.securebank.account.exception.ErrorCode;
import com.securebank.account.repository.CustomerAccountLockRepository;
import com.securebank.account.security.CallerContext;
import com.securebank.account.service.AccountService;
import com.securebank.account.support.AbstractIntegrationTest;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * The per-customer open-account limit (5 by default) and the "one PENDING application per customer and type" rule
 * (D6) must hold when applications race. Check and insert are serialised per customer by a SELECT ... FOR UPDATE on
 * the customer's lock row (V3 migration). These tests run on H2 in MySQL mode, which honours row locks; the
 * InnoDB-specific behaviour and the V4 unique index on pending_slot are not exercised here.
 */
class OpenAccountLimitConcurrencyTest extends AbstractIntegrationTest {

    private static final int LIMIT = 5;

    @Autowired AccountService accountService;
    @Autowired CustomerAccountLockRepository lockRepository;

    @BeforeEach
    void clearLockRows() {
        lockRepository.deleteAllInBatch();
    }

    private record Outcome(int created, int limitExceeded, int alreadyPending, List<Throwable> unexpected) {
    }

    private Outcome race(UUID customer, List<AccountType> types) throws Exception {
        int threads = types.size();
        CallerContext caller = new CallerContext("customer-user", false, customer);
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch ready = new CountDownLatch(threads);
        CountDownLatch go = new CountDownLatch(1);
        List<Future<Throwable>> futures = new ArrayList<>();
        for (AccountType type : types) {
            futures.add(pool.submit(() -> {
                ready.countDown();
                go.await();
                try {
                    accountService.createAccount(new CreateAccountRequest(customer, type, "INR"), caller);
                    return null;
                } catch (Throwable t) {
                    return t;
                }
            }));
        }
        assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
        go.countDown();
        int created = 0;
        int limit = 0;
        int pending = 0;
        List<Throwable> unexpected = new ArrayList<>();
        for (Future<Throwable> f : futures) {
            Throwable t = f.get(60, TimeUnit.SECONDS);
            if (t == null) {
                created++;
            } else if (t instanceof ApiException e && e.getErrorCode() == ErrorCode.ACCOUNT_LIMIT_EXCEEDED) {
                limit++;
            } else if (t instanceof ApiException e && e.getErrorCode() == ErrorCode.APPLICATION_ALREADY_PENDING) {
                pending++;
            } else {
                unexpected.add(t);
            }
        }
        pool.shutdownNow();
        return new Outcome(created, limit, pending, unexpected);
    }

    private long countOf(UUID customer, AccountStatus status) {
        return accountRepository.findAll().stream()
                .filter(a -> a.getCustomerId().equals(customer) && a.getStatus() == status).count();
    }

    @Test
    void concurrentApplicationsOfTheSameTypeAdmitExactlyOnePending() throws Exception {
        UUID customer = UUID.randomUUID();
        givenCustomer(customer, "ACTIVE");

        Outcome outcome = race(customer, java.util.Collections.nCopies(8, AccountType.SAVINGS));

        assertThat(outcome.unexpected()).isEmpty();
        assertThat(outcome.created()).isEqualTo(1);
        assertThat(outcome.alreadyPending()).isEqualTo(7);
        assertThat(countOf(customer, AccountStatus.PENDING)).isEqualTo(1);
        assertThat(lockRepository.count()).isEqualTo(1);
    }

    @Test
    void oneApplicationPerTypeMayBePendingAtTheSameTime() throws Exception {
        UUID customer = UUID.randomUUID();
        givenCustomer(customer, "ACTIVE");

        Outcome outcome = race(customer, List.of(AccountType.SAVINGS, AccountType.CURRENT, AccountType.SAVINGS,
                AccountType.CURRENT));

        assertThat(outcome.unexpected()).isEmpty();
        assertThat(outcome.created()).isEqualTo(2);
        assertThat(outcome.alreadyPending()).isEqualTo(2);
    }

    @Test
    void applicationsAtTheOpenAccountLimitAdmitExactlyTheRemainingSlot() throws Exception {
        UUID customer = UUID.randomUUID();
        givenCustomer(customer, "ACTIVE");
        for (int i = 0; i < LIMIT - 1; i++) {
            insertAccount(customer);
        }

        Outcome outcome = race(customer, List.of(AccountType.SAVINGS, AccountType.CURRENT, AccountType.SAVINGS,
                AccountType.CURRENT));

        assertThat(outcome.unexpected()).isEmpty();
        assertThat(outcome.created()).isEqualTo(1);
        long openOrPending = accountRepository.countByCustomerIdAndStatusNotIn(customer,
                List.of(AccountStatus.CLOSED, AccountStatus.REJECTED));
        assertThat(openOrPending).isEqualTo(LIMIT);
    }

    @Test
    void rejectedApplicationsDoNotCountTowardsTheLimitAndAllowReapplication() {
        UUID customer = UUID.randomUUID();
        givenCustomer(customer, "ACTIVE");
        CallerContext me = new CallerContext("customer-user", false, customer);
        CallerContext admin = new CallerContext("admin-user", true, null);

        for (int i = 0; i < LIMIT + 2; i++) {
            var created = accountService.createAccount(new CreateAccountRequest(customer, AccountType.SAVINGS, "INR"), me);
            accountService.reject(created.id(), "not suitable", admin);
        }

        assertThat(countOf(customer, AccountStatus.REJECTED)).isEqualTo(LIMIT + 2);
        accountService.createAccount(new CreateAccountRequest(customer, AccountType.SAVINGS, "INR"), me);
        assertThat(countOf(customer, AccountStatus.PENDING)).isEqualTo(1);
    }
}
