package com.securebank.account.repository;

import com.securebank.account.domain.CustomerAccountLock;
import jakarta.persistence.LockModeType;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface CustomerAccountLockRepository extends JpaRepository<CustomerAccountLock, UUID> {

    /** SELECT ... FOR UPDATE on the customer's lock row; blocks until a concurrent creation commits or rolls back. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select l from CustomerAccountLock l where l.customerId = :customerId")
    Optional<CustomerAccountLock> lockByCustomerId(@Param("customerId") UUID customerId);
}
