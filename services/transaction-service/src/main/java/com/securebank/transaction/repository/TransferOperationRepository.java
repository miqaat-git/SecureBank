package com.securebank.transaction.repository;

import com.securebank.transaction.domain.OperationType;
import com.securebank.transaction.domain.TransferOperation;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface TransferOperationRepository extends JpaRepository<TransferOperation, UUID> {

    Optional<TransferOperation> findByTransferIdAndOperationType(UUID transferId, OperationType operationType);

    List<TransferOperation> findByTransferIdOrderByCreatedAtAsc(UUID transferId);
}
