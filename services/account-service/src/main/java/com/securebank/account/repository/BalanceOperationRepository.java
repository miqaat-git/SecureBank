package com.securebank.account.repository;

import com.securebank.account.domain.BalanceOperation;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface BalanceOperationRepository extends JpaRepository<BalanceOperation, UUID> {

    Optional<BalanceOperation> findByIdempotencyKey(String idempotencyKey);

    Optional<BalanceOperation> findByReversesKey(String reversesKey);
}
