package com.securebank.account.service;

import com.securebank.account.domain.Account;
import com.securebank.account.domain.AccountStatus;
import com.securebank.account.dto.InternalBalanceView;
import com.securebank.account.dto.PageResponse;
import com.securebank.account.exception.ApiException;
import com.securebank.account.exception.ErrorCode;
import com.securebank.account.repository.AccountRepository;
import com.securebank.account.repository.AccountSpecifications;
import java.time.Clock;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Read-only balance listing for the Transaction Service reconciliation view. Never modifies an account. */
@Service
@Transactional(readOnly = true)
public class InternalBalanceQueryService {

    public static final int MAX_PAGE_SIZE = 100;

    private final AccountRepository accounts;
    private final Clock clock;

    public InternalBalanceQueryService(AccountRepository accounts, Clock clock) {
        this.accounts = accounts;
        this.clock = clock;
    }

    public PageResponse<InternalBalanceView> list(AccountStatus status, String currency, UUID accountId,
                                                  int page, int size) {
        if (page < 0 || size < 1 || size > MAX_PAGE_SIZE) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED,
                    "page must be >= 0 and size between 1 and " + MAX_PAGE_SIZE + ".");
        }
        String normalizedCurrency = currency == null || currency.isBlank() ? null : currency.trim().toUpperCase();
        Specification<Account> spec = AccountSpecifications.hasStatus(status)
                .and((root, query, cb) -> normalizedCurrency == null ? cb.conjunction()
                        : cb.equal(root.get("currency"), normalizedCurrency))
                .and((root, query, cb) -> accountId == null ? cb.conjunction() : cb.equal(root.get("id"), accountId));
        Page<Account> result = accounts.findAll(spec, PageRequest.of(page, size, Sort.by("id").ascending()));
        return PageResponse.from(result, a -> new InternalBalanceView(a.getId(), a.getCurrency(), a.getStatus(),
                a.getAvailableBalance(), a.getVersion(), clock.instant()));
    }
}
