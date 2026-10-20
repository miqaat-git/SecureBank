package com.securebank.transaction.repository;

import com.securebank.transaction.domain.Transfer;
import com.securebank.transaction.domain.TransferStatus;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface TransferRepository extends JpaRepository<Transfer, UUID>, JpaSpecificationExecutor<Transfer> {

    Optional<Transfer> findByIdempotencyKey(String idempotencyKey);

    /** Atomic compare-and-set lease. Returns 1 if this worker now owns the transfer, else 0. */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("update Transfer t set t.lockOwner = :owner, t.lockExpiresAt = :expiry "
            + "where t.id = :id and (t.lockExpiresAt is null or t.lockExpiresAt < :now)")
    int acquireLease(@Param("id") UUID id, @Param("owner") String owner, @Param("now") Instant now,
                     @Param("expiry") Instant expiry);

    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("update Transfer t set t.lockOwner = null, t.lockExpiresAt = null "
            + "where t.id = :id and t.lockOwner = :owner")
    int releaseLease(@Param("id") UUID id, @Param("owner") String owner);

    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("update Transfer t set t.nextAttemptAt = :at where t.id = :id and t.status in :statuses")
    int scheduleNextAttempt(@Param("id") UUID id, @Param("at") Instant at,
                            @Param("statuses") Collection<TransferStatus> statuses);

    @Query("select t.id from Transfer t where t.status in :statuses and t.nextAttemptAt <= :now "
            + "and (t.lockExpiresAt is null or t.lockExpiresAt < :now) order by t.nextAttemptAt asc")
    List<UUID> findRecoverableIds(@Param("statuses") Collection<TransferStatus> statuses,
                                  @Param("now") Instant now, Pageable pageable);
}
