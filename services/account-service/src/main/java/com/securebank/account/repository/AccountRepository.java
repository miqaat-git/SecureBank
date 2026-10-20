package com.securebank.account.repository;

import com.securebank.account.domain.Account;
import com.securebank.account.domain.AccountStatus;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;

public interface AccountRepository extends JpaRepository<Account, UUID>, JpaSpecificationExecutor<Account> {

    Optional<Account> findByAccountNumber(String accountNumber);

    boolean existsByAccountNumber(String accountNumber);

    long countByCustomerIdAndStatusNot(UUID customerId, AccountStatus status);
}
