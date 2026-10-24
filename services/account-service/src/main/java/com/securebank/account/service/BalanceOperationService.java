package com.securebank.account.service;

import com.securebank.account.domain.Account;
import com.securebank.account.domain.BalanceOperation;
import com.securebank.account.domain.BalanceOperationType;
import com.securebank.account.domain.Money;
import com.securebank.account.dto.BalanceOperationRequest;
import com.securebank.account.dto.BalanceOperationResponse;
import com.securebank.account.dto.InternalAccountView;
import com.securebank.account.dto.InternalOperationRequest;
import com.securebank.account.dto.InternalOperationResponse;
import com.securebank.account.exception.ApiException;
import com.securebank.account.exception.ErrorCode;
import com.securebank.account.mapper.AccountMapper;
import com.securebank.account.repository.AccountRepository;
import com.securebank.account.repository.BalanceOperationRepository;
import java.math.BigDecimal;
import java.time.Clock;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.ConcurrencyFailureException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
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

    /** Legacy endpoint ({@code /internal/v1/accounts/{id}/balance-operations}); DEBIT and CREDIT only. */
    public BalanceOperationResponse apply(UUID accountId, BalanceOperationRequest request, String actor) {
        if (request.operationType() == BalanceOperationType.REVERSE_DEBIT) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED,
                    "REVERSE_DEBIT is only supported by /internal/accounts/{accountId}/operations.");
        }
        Command command = new Command(accountId, request.operationType(), Money.normalizePositive(request.amount()),
                request.idempotencyKey(), request.referenceId(), null, null);
        Result result = execute(command, actor);
        return AccountMapper.toOperationResponse(result.operation(), result.replayed());
    }

    /** Canonical contract ({@code POST /internal/accounts/{id}/operations}); operationId is the idempotency key. */
    public InternalOperationResponse applyOperation(UUID accountId, InternalOperationRequest request, String actor) {
        boolean reversal = request.type() == BalanceOperationType.REVERSE_DEBIT;
        if (reversal != (request.reversesOperationId() != null)) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED,
                    "reversesOperationId is required for REVERSE_DEBIT and must be absent otherwise.");
        }
        Command command = new Command(accountId, request.type(), Money.normalizePositive(request.amount()),
                request.operationId().toString(), request.transferId(),
                reversal ? request.reversesOperationId().toString() : null, request.currency());
        Result result = execute(command, actor);
        return toInternalResponse(result.operation(), result.replayed());
    }

    /** Status lookup used by the Transaction Service after an ambiguous outcome. 404 means never recorded. */
    @Transactional(readOnly = true)
    public InternalOperationResponse findOperation(UUID accountId, UUID operationId) {
        return operationRepository.findByIdempotencyKey(operationId.toString())
                .filter(o -> o.getAccountId().equals(accountId))
                .map(o -> toInternalResponse(o, true))
                .orElseThrow(() -> new ApiException(ErrorCode.OPERATION_NOT_FOUND, "Operation not found."));
    }

    @Transactional(readOnly = true)
    public InternalAccountView findAccountView(UUID accountId) {
        Account account = accountRepository.findById(accountId)
                .orElseThrow(() -> new ApiException(ErrorCode.ACCOUNT_NOT_FOUND, "Account not found."));
        return new InternalAccountView(account.getId(), account.getCustomerId(), account.getStatus(),
                account.getCurrency());
    }

    private static InternalOperationResponse toInternalResponse(BalanceOperation o, boolean replayed) {
        return new InternalOperationResponse(InternalOperationResponse.SUCCEEDED,
                UUID.fromString(o.getIdempotencyKey()), o.getAccountId(), o.getOperationType(), o.getAmount(),
                o.getResultingBalance(), o.getReferenceId(), replayed, o.getCreatedAt());
    }

    private Result execute(Command command, String actor) {
        for (int attempt = 1; attempt <= MAX_ATTEMPTS; attempt++) {
            try {
                return transactionTemplate.execute(status -> applyOnce(command, actor));
            } catch (ConcurrencyFailureException ex) {
                // Optimistic-lock failure (or a lock timeout / deadlock victim): re-read and retry.
                log.info("Concurrency conflict on account {} (attempt {}/{})", command.accountId(), attempt,
                        MAX_ATTEMPTS);
            } catch (DataIntegrityViolationException ex) {
                // A concurrent request committed first: same idempotency key, or another reversal of the same debit.
                Optional<BalanceOperation> existing = operationRepository.findByIdempotencyKey(command.key());
                if (existing.isPresent()) {
                    return replay(existing.get(), command);
                }
                if (command.reversesKey() != null
                        && operationRepository.findByReversesKey(command.reversesKey()).isPresent()) {
                    throw new ApiException(ErrorCode.OPERATION_ALREADY_REVERSED,
                            "The original operation has already been reversed.");
                }
                throw ex;
            }
        }
        throw new ApiException(ErrorCode.CONCURRENT_MODIFICATION,
                "The account is being modified concurrently. Retry the operation with the same idempotency key.");
    }

    private Result applyOnce(Command command, String actor) {
        Optional<BalanceOperation> existing = operationRepository.findByIdempotencyKey(command.key());
        if (existing.isPresent()) {
            return replay(existing.get(), command);
        }

        Account account = accountRepository.findById(command.accountId())
                .orElseThrow(() -> new ApiException(ErrorCode.ACCOUNT_NOT_FOUND, "Account not found."));
        if (command.currency() != null && !command.currency().equals(account.getCurrency())) {
            throw new ApiException(ErrorCode.UNSUPPORTED_CURRENCY, "Currency does not match the account currency.");
        }
        if (command.type() == BalanceOperationType.REVERSE_DEBIT) {
            requireReversible(command);
        }

        var now = clock.instant();
        if (command.type() == BalanceOperationType.DEBIT) {
            account.debit(command.amount(), now);
        } else {
            // CREDIT and REVERSE_DEBIT both add to the balance.
            account.credit(command.amount(), now);
        }
        // Flush explicitly so a version conflict or constraint violation surfaces here, inside the transaction.
        accountRepository.saveAndFlush(account);

        BalanceOperation operation = BalanceOperation.record(command.accountId(), command.key(), command.type(),
                command.amount(), account.getAvailableBalance(), command.referenceId(), command.reversesKey(), now,
                actor);
        operationRepository.saveAndFlush(operation);
        log.info("Applied {} of {} to account {} (operation {})", command.type(), command.amount(),
                command.accountId(), operation.getId());
        return new Result(operation, false);
    }

    /** A reversal must name an existing DEBIT on the same account with the same amount and transfer, once only. */
    private void requireReversible(Command command) {
        operationRepository.findByIdempotencyKey(command.reversesKey())
                .filter(o -> o.getOperationType() == BalanceOperationType.DEBIT
                        && o.getAccountId().equals(command.accountId())
                        && o.getAmount().compareTo(command.amount()) == 0
                        && o.getReferenceId().equals(command.referenceId()))
                .orElseThrow(() -> new ApiException(ErrorCode.INVALID_REVERSAL,
                        "No matching debit exists on this account for the reversal."));
        if (operationRepository.findByReversesKey(command.reversesKey()).isPresent()) {
            throw new ApiException(ErrorCode.OPERATION_ALREADY_REVERSED,
                    "The original operation has already been reversed.");
        }
    }

    private Result replay(BalanceOperation existing, Command command) {
        if (!existing.matches(command.accountId(), command.type(), command.amount(), command.referenceId())
                || !Objects.equals(existing.getReversesKey(), command.reversesKey())) {
            throw new ApiException(ErrorCode.IDEMPOTENCY_KEY_REUSED,
                    "The idempotency key was already used with a different request.");
        }
        return new Result(existing, true);
    }

    private record Command(UUID accountId, BalanceOperationType type, BigDecimal amount, String key,
                           UUID referenceId, String reversesKey, String currency) {
    }

    private record Result(BalanceOperation operation, boolean replayed) {
    }
}
