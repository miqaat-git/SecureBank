package com.securebank.account.support;

import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.securebank.account.client.CustomerClient;
import com.securebank.account.client.CustomerSummary;
import com.securebank.account.domain.Account;
import com.securebank.account.domain.AccountType;
import com.securebank.account.repository.AccountRepository;
import com.securebank.account.repository.BalanceOperationRepository;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

/** Shared fixtures: MockMvc, repositories, a mocked Customer Service client and a clean database per test. */
@AccountIntegrationTest
public abstract class AbstractIntegrationTest {

    private static final AtomicLong ACCOUNT_NUMBER_SEQUENCE = new AtomicLong(1_000_000_000_000L);

    @Autowired
    protected MockMvc mockMvc;

    @Autowired
    protected ObjectMapper objectMapper;

    @Autowired
    protected AccountRepository accountRepository;

    @Autowired
    protected BalanceOperationRepository balanceOperationRepository;

    @MockitoBean
    protected CustomerClient customerClient;

    @BeforeEach
    protected void cleanDatabase() {
        reset(customerClient);
        balanceOperationRepository.deleteAllInBatch();
        accountRepository.deleteAllInBatch();
    }

    protected void givenCustomer(UUID customerId, String status) {
        when(customerClient.findCustomer(customerId)).thenReturn(Optional.of(new CustomerSummary(customerId, status)));
    }

    /** Inserts an ACTIVE account directly (bypassing the API) with a unique 13-digit number. */
    protected Account insertAccount(UUID customerId) {
        String number = String.valueOf(ACCOUNT_NUMBER_SEQUENCE.incrementAndGet());
        return accountRepository.saveAndFlush(
                Account.open(customerId, number, AccountType.SAVINGS, "INR", Instant.now()));
    }
}
