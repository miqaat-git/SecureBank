package com.securebank.account.repository;

import com.securebank.account.domain.Account;
import com.securebank.account.domain.AccountStatus;
import com.securebank.account.domain.AccountType;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;

public interface AccountRepository extends JpaRepository<Account, UUID>, JpaSpecificationExecutor<Account> {

    Optional<Account> findByAccountNumber(String accountNumber);

    boolean existsByAccountNumber(String accountNumber);

    long countByCustomerIdAndStatusNot(UUID customerId, AccountStatus status);

    /** Open accounts and pending applications: everything except CLOSED and REJECTED. */
    long countByCustomerIdAndStatusNotIn(UUID customerId, java.util.Collection<AccountStatus> statuses);

    boolean existsByCustomerIdAndAccountTypeAndStatus(UUID customerId, AccountType type, AccountStatus status);
}
