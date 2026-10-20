package com.securebank.ledger.repository;

import com.securebank.ledger.entity.JournalEntry;
import com.securebank.ledger.enums.EntryStatus;
import jakarta.persistence.criteria.Predicate;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import org.springframework.data.jpa.domain.Specification;

public final class JournalEntrySpecifications {

    private JournalEntrySpecifications() {
    }

    /** All filters are optional. The date range is [from, to) on createdAt. */
    public static Specification<JournalEntry> matching(EntryStatus status, String reference,
                                                       Instant from, Instant to) {
        return (root, query, cb) -> {
            List<Predicate> predicates = new ArrayList<>();
            if (status != null) {
                predicates.add(cb.equal(root.get("status"), status));
            }
            if (reference != null && !reference.isBlank()) {
                predicates.add(cb.equal(root.get("businessReference"), reference));
            }
            if (from != null) {
                predicates.add(cb.greaterThanOrEqualTo(root.<Instant>get("createdAt"), from));
            }
            if (to != null) {
                predicates.add(cb.lessThan(root.<Instant>get("createdAt"), to));
            }
            return cb.and(predicates.toArray(new Predicate[0]));
        };
    }
}
