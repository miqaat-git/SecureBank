package com.securebank.account.mapper;

import com.securebank.account.domain.Account;
import com.securebank.account.domain.BalanceOperation;
import com.securebank.account.dto.AccountBalanceResponse;
import com.securebank.account.dto.AccountResponse;
import com.securebank.account.dto.AccountStatusResponse;
import com.securebank.account.dto.BalanceOperationResponse;

/** Explicit entity -> DTO mapping. Entities never leave the service layer. */
public final class AccountMapper {

    private AccountMapper() {
    }

    public static AccountResponse toResponse(Account account) {
        return new AccountResponse(
                account.getId(),
                account.getCustomerId(),
                account.getAccountNumber(),
                account.getAccountType(),
                account.getStatus(),
                account.getCurrency(),
                account.getAvailableBalance(),
                account.getCreatedAt(),
                account.getUpdatedAt(),
                account.getVersion());
    }

    public static AccountStatusResponse toStatusResponse(Account account) {
        return new AccountStatusResponse(
                account.getId(),
                account.getAccountNumber(),
                account.getStatus(),
                account.getStatusChangedAt());
    }

    public static AccountBalanceResponse toBalanceResponse(Account account) {
        return new AccountBalanceResponse(
                account.getId(),
                account.getCurrency(),
                account.getAvailableBalance(),
                account.getUpdatedAt(),
                account.getVersion());
    }

    public static BalanceOperationResponse toOperationResponse(BalanceOperation operation, boolean replayed) {
        return new BalanceOperationResponse(
                operation.getId(),
                operation.getAccountId(),
                operation.getOperationType(),
                operation.getAmount(),
                operation.getResultingBalance(),
                operation.getReferenceId(),
                replayed,
                operation.getCreatedAt());
    }
}
