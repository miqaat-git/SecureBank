package com.securebank.account.service;

import com.securebank.account.domain.Account;
import com.securebank.account.domain.BalanceOperation;
import com.securebank.account.domain.BalanceOperationType;
import com.securebank.account.domain.Money;
import com.securebank.account.dto.BalanceOperationRequest;
import com.securebank.account.dto.BalanceOperationResponse;
import com.securebank.account.exception.ApiException;
import com.securebank.account.exception.ErrorCode;
import com.securebank.account.mapper.AccountMapper;
import com.securebank.account.repository.AccountRepository;
import com.securebank.account.repository.BalanceOperationRepository;
import java.math.BigDecimal;
import java.time.Clock;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.ConcurrencyFailureException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Internal, idempotent balance adjustments for the Transaction Service. Not reachable by customers or admins
 * (see SecurityConfig: {@code /internal/**} requires ROLE_TRANSACTION_SERVICE).
 *
 * <p>Guarantees:
 * <ul>
 *   <li><b>Idempotency</b>: the same idempotency key is applied at most once (unique DB constraint). A replay with
 *       an identical payload returns the stored result; a replay with a different payload is rejected (409).</li>
 *   <li><b>No lost updates</b>: the account row is protected by an optimistic version check. A conflicting writer
 *       re-reads and retries a bounded number of times, then fails with 409 instead of overwriting.</li>
 *   <li><b>Atomicity inside this service</b>: the balance change and its operation record are committed in one
 *       local database transaction. This is NOT a distributed transaction: the matching debit/credit on another
 *       account and the ledger entries are coordinated by the Transaction Service (see README).</li>
 * </ul>
 */
@Service
public class BalanceOperationService {

    private static final Logger log = LoggerFactory.getLogger(BalanceOperationService.class);

    static final int MAX_ATTEMPTS = 5;

    private final AccountRepository accountRepository;
    private final BalanceOperationRepository operationRepository;
    private final TransactionTemplate transactionTemplate;
    private final Clock clock;

    public BalanceOperationService(AccountRepository accountRepository,
                                   BalanceOperationRepository operationRepository,
                                   PlatformTransactionManager transactionManager, Clock clock) {
        this.accountRepository = accountRepository;
        this.operationRepository = operationRepository;
        this.transactionTemplate = new TransactionTemplate(transactionManager);
        this.clock = clock;
    }

    public BalanceOperationResponse apply(UUID accountId, BalanceOperationRequest request, String actor) {
        BigDecimal amount = Money.normalizePositive(request.amount());

        for (int attempt = 1; attempt <= MAX_ATTEMPTS; attempt++) {
            try {
                return transactionTemplate.execute(status -> applyOnce(accountId, request, amount, actor));
            } catch (ConcurrencyFailureException ex) {
                // Optimistic-lock failure (or a lock timeout / deadlock victim): re-read and retry.
                log.info("Concurrency conflict on account {} (attempt {}/{})", accountId, attempt, MAX_ATTEMPTS);
            } catch (DataIntegrityViolationException ex) {
                // A concurrent request with the same idempotency key committed first.
                Optional<BalanceOperation> existing = operationRepository
                        .findByIdempotencyKey(request.idempotencyKey());
                if (existing.isPresent()) {
                    return replay(existing.get(), accountId, request, amount);
                }
                throw ex;
            }
        }
        throw new ApiException(ErrorCode.CONCURRENT_MODIFICATION,
                "The account is being modified concurrently. Retry the operation with the same idempotency key.");
    }

    private BalanceOperationResponse applyOnce(UUID accountId, BalanceOperationRequest request, BigDecimal amount,
                                               String actor) {
        Optional<BalanceOperation> existing = operationRepository.findByIdempotencyKey(request.idempotencyKey());
        if (existing.isPresent()) {
            return replay(existing.get(), accountId, request, amount);
        }

        Account account = accountRepository.findById(accountId)
                .orElseThrow(() -> new ApiException(ErrorCode.ACCOUNT_NOT_FOUND, "Account not found."));
        var now = clock.instant();
        if (request.operationType() == BalanceOperationType.CREDIT) {
            account.credit(amount, now);
        } else {
            account.debit(amount, now);
        }
        // Flush explicitly so a version conflict or constraint violation surfaces here, inside the transaction.
        accountRepository.saveAndFlush(account);

        BalanceOperation operation = BalanceOperation.record(accountId, request.idempotencyKey(),
                request.operationType(), amount, account.getAvailableBalance(), request.referenceId(), now, actor);
        operationRepository.saveAndFlush(operation);
        log.info("Applied {} of {} to account {} (operation {})", request.operationType(), amount, accountId,
                operation.getId());
        return AccountMapper.toOperationResponse(operation, false);
    }

    private BalanceOperationResponse replay(BalanceOperation existing, UUID accountId,
                                            BalanceOperationRequest request, BigDecimal amount) {
        if (!existing.matches(accountId, request.operationType(), amount, request.referenceId())) {
            throw new ApiException(ErrorCode.IDEMPOTENCY_KEY_REUSED,
                    "The idempotency key was already used with a different request.");
        }
        return AccountMapper.toOperationResponse(existing, true);
    }
}
