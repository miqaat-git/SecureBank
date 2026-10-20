package com.securebank.account.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.securebank.account.domain.Account;
import com.securebank.account.domain.AccountType;
import com.securebank.account.domain.BalanceOperation;
import com.securebank.account.domain.BalanceOperationType;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.autoconfigure.orm.jpa.TestEntityManager;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.test.context.ActiveProfiles;

/** Entity mappings, monetary precision and database uniqueness constraints on the isolated H2 test database. */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@ActiveProfiles("test")
class AccountRepositoryTest {

    private static final Instant NOW = Instant.parse("2026-03-01T10:15:30.123456Z");

    @Autowired
    private AccountRepository accountRepository;

    @Autowired
    private BalanceOperationRepository operationRepository;

    @Autowired
    private TestEntityManager entityManager;

    private Account newAccount(String number) {
        return Account.open(UUID.randomUUID(), number, AccountType.CURRENT, "INR", NOW);
    }

    @Test
    void persistsAndReloadsAllFieldsIncludingMoneyPrecision() {
        Account account = newAccount("2000000000001");
        account.credit(new BigDecimal("12345678901234.56"), NOW);
        Account saved = accountRepository.saveAndFlush(account);
        UUID id = saved.getId();
        entityManager.clear();

        Account loaded = accountRepository.findById(id).orElseThrow();
        assertThat(loaded.getAvailableBalance()).isEqualByComparingTo("12345678901234.56");
        assertThat(loaded.getAvailableBalance().scale()).isEqualTo(2);
        assertThat(loaded.getAccountNumber()).isEqualTo("2000000000001");
        assertThat(loaded.getCustomerId()).isEqualTo(account.getCustomerId());
        assertThat(loaded.getAccountType()).isEqualTo(AccountType.CURRENT);
        assertThat(loaded.getCurrency()).isEqualTo("INR");
        assertThat(Duration.between(NOW, loaded.getCreatedAt()).abs()).isLessThan(Duration.ofMillis(1));
        assertThat(loaded.getVersion()).isNotNull();
    }

    @Test
    void versionIsIncrementedOnEveryUpdate() {
        Account saved = accountRepository.saveAndFlush(newAccount("2000000000002"));
        Long first = saved.getVersion();

        saved.credit(new BigDecimal("1.00"), NOW);
        accountRepository.saveAndFlush(saved);
        entityManager.clear();

        assertThat(accountRepository.findById(saved.getId()).orElseThrow().getVersion()).isGreaterThan(first);
    }

    @Test
    void databaseRejectsDuplicateAccountNumbers() {
        accountRepository.saveAndFlush(newAccount("2000000000003"));
        assertThatThrownBy(() -> accountRepository.saveAndFlush(newAccount("2000000000003")))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void findsByAccountNumber() {
        Account saved = accountRepository.saveAndFlush(newAccount("2000000000004"));
        assertThat(accountRepository.findByAccountNumber("2000000000004")).isPresent()
                .get().extracting(Account::getId).isEqualTo(saved.getId());
        assertThat(accountRepository.existsByAccountNumber("2000000000004")).isTrue();
        assertThat(accountRepository.findByAccountNumber("9999999999999")).isEmpty();
    }

    @Test
    void databaseRejectsDuplicateIdempotencyKeys() {
        Account account = accountRepository.saveAndFlush(newAccount("2000000000005"));
        UUID reference = UUID.randomUUID();
        operationRepository.saveAndFlush(BalanceOperation.record(account.getId(), "key-0001-abcdef",
                BalanceOperationType.CREDIT, new BigDecimal("5.00"), new BigDecimal("5.00"), reference, NOW, "svc"));

        assertThatThrownBy(() -> operationRepository.saveAndFlush(BalanceOperation.record(account.getId(),
                "key-0001-abcdef", BalanceOperationType.CREDIT, new BigDecimal("5.00"), new BigDecimal("10.00"),
                reference, NOW, "svc"))).isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void balanceOperationRoundTripKeepsAmountsAndFindsByKey() {
        Account account = accountRepository.saveAndFlush(newAccount("2000000000006"));
        UUID reference = UUID.randomUUID();
        operationRepository.saveAndFlush(BalanceOperation.record(account.getId(), "key-0002-abcdef",
                BalanceOperationType.DEBIT, new BigDecimal("0.10"), new BigDecimal("0.00"), reference, NOW, "svc"));
        entityManager.clear();

        BalanceOperation loaded = operationRepository.findByIdempotencyKey("key-0002-abcdef").orElseThrow();
        assertThat(loaded.getAmount()).isEqualByComparingTo("0.10");
        assertThat(loaded.getOperationType()).isEqualTo(BalanceOperationType.DEBIT);
        assertThat(loaded.getReferenceId()).isEqualTo(reference);
        assertThat(loaded.isNew()).isFalse();
    }
}
