package com.securebank.account.repository;

import com.securebank.account.domain.Account;
import com.securebank.account.domain.AccountStatus;
import com.securebank.account.domain.AccountType;
import java.util.UUID;
import org.springframework.data.jpa.domain.Specification;

/** Optional filters for account searches. A null argument means "do not filter on this field". */
public final class AccountSpecifications {

    private AccountSpecifications() {
    }

    public static Specification<Account> hasCustomerId(UUID customerId) {
        return (root, query, cb) -> customerId == null
                ? cb.conjunction()
                : cb.equal(root.get("customerId"), customerId);
    }

    public static Specification<Account> hasStatus(AccountStatus status) {
        return (root, query, cb) -> status == null
                ? cb.conjunction()
                : cb.equal(root.get("status"), status);
    }

    public static Specification<Account> hasType(AccountType type) {
        return (root, query, cb) -> type == null
                ? cb.conjunction()
                : cb.equal(root.get("accountType"), type);
    }
}
