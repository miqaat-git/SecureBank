package com.securebank.transaction.repository;

import com.securebank.transaction.domain.TransferHistory;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface TransferHistoryRepository extends JpaRepository<TransferHistory, Long> {

    List<TransferHistory> findByTransferIdOrderByIdAsc(UUID transferId);
}
