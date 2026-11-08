package com.securebank.account.repository;

import com.securebank.account.domain.AccountStatusHistory;
import java.util.List;
import java.util.UUID;
import org.springframework.data.repository.Repository;

/** Insert and read only: history rows are never updated or deleted. */
public interface AccountStatusHistoryRepository extends Repository<AccountStatusHistory, Long> {

    AccountStatusHistory save(AccountStatusHistory entry);

    List<AccountStatusHistory> findByAccountIdOrderByIdAsc(UUID accountId);
}
