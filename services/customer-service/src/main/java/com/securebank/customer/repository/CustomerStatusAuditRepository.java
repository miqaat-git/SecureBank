package com.securebank.customer.repository;

import com.securebank.customer.entity.CustomerStatusAudit;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.repository.Repository;

/** Insert and read only: audit records can never be updated or deleted through this repository. */
public interface CustomerStatusAuditRepository extends Repository<CustomerStatusAudit, Long> {

    CustomerStatusAudit save(CustomerStatusAudit audit);

    Page<CustomerStatusAudit> findByCustomerId(UUID customerId, Pageable pageable);

    long countByCustomerId(UUID customerId);
}
