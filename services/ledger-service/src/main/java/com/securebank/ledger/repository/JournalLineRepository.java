package com.securebank.ledger.repository;

import com.securebank.ledger.entity.JournalLine;
import java.time.Instant;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface JournalLineRepository extends JpaRepository<JournalLine, UUID> {

    /** Lines of one account with postedAt in [from, to). The entry is fetched to expose its reference. */
    @EntityGraph(attributePaths = "entry")
    Page<JournalLine> findByAccountReferenceAndPostedAtGreaterThanEqualAndPostedAtLessThan(
            String accountReference, Instant from, Instant to, Pageable pageable);

    @Query("select new com.securebank.ledger.repository.AccountTotals("
            + "sum(l.debitAmount), sum(l.creditAmount), count(l)) "
            + "from JournalLine l "
            + "where l.accountReference = :account and l.currency = :currency "
            + "and l.postedAt >= :from and l.postedAt < :to")
    AccountTotals totalsFor(@Param("account") String account,
                            @Param("currency") String currency,
                            @Param("from") Instant from,
                            @Param("to") Instant to);

    /**
     * Read-only grouped totals for a bounded set of account references (reconciliation support). Reversal entries are
     * ordinary lines with opposite sides, so summing every line nets a reversal against its original exactly once.
     */
    @Query("select new com.securebank.ledger.repository.AccountCurrencyTotals("
            + "l.accountReference, l.currency, sum(l.debitAmount), sum(l.creditAmount), count(l)) "
            + "from JournalLine l where l.accountReference in :accounts "
            + "group by l.accountReference, l.currency")
    java.util.List<AccountCurrencyTotals> totalsByAccount(@Param("accounts") java.util.Collection<String> accounts);
}
