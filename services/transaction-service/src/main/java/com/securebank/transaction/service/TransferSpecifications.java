package com.securebank.transaction.service;

import com.securebank.transaction.domain.Transfer;
import com.securebank.transaction.security.Caller;
import jakarta.persistence.criteria.Predicate;
import java.util.ArrayList;
import java.util.List;
import org.springframework.data.jpa.domain.Specification;

public final class TransferSpecifications {

    private TransferSpecifications() {
    }

    /** Builds filters; customers are always restricted to their own transfers. */
    public static Specification<Transfer> from(TransferSearchCriteria c, Caller caller) {
        return (root, query, cb) -> {
            List<Predicate> p = new ArrayList<>();
            if (!caller.operator()) {
                if (caller.customerId() == null) {
                    return cb.disjunction();
                }
                p.add(cb.equal(root.get("initiatorCustomerId"), caller.customerId()));
            }
            if (c.status() != null) {
                p.add(cb.equal(root.get("status"), c.status()));
            }
            if (c.sourceAccountId() != null) {
                p.add(cb.equal(root.get("sourceAccountId"), c.sourceAccountId()));
            }
            if (c.destinationAccountId() != null) {
                p.add(cb.equal(root.get("destinationAccountId"), c.destinationAccountId()));
            }
            if (c.createdFrom() != null) {
                p.add(cb.greaterThanOrEqualTo(root.<java.time.Instant>get("createdAt"), c.createdFrom()));
            }
            if (c.createdTo() != null) {
                p.add(cb.lessThan(root.<java.time.Instant>get("createdAt"), c.createdTo()));
            }
            return cb.and(p.toArray(new Predicate[0]));
        };
    }
}
