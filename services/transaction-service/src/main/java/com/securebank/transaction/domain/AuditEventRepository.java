package com.securebank.transaction.domain;

import java.util.List;
import java.util.UUID;
import org.springframework.data.repository.Repository;

/** Insert and read only: audit rows are never updated or deleted. */
public interface AuditEventRepository extends Repository<AuditEvent, Long> {

    AuditEvent save(AuditEvent event);

    List<AuditEvent> findByTransferIdOrderByIdAsc(UUID transferId);

    List<AuditEvent> findByEventTypeOrderByIdAsc(String eventType);

    long count();
}
