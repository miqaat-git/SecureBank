package com.securebank.account.service;

import com.securebank.account.client.CustomerClient;
import com.securebank.account.client.CustomerSummary;
import com.securebank.account.config.AccountProperties;
import com.securebank.account.domain.Account;
import com.securebank.account.domain.AccountStatus;
import com.securebank.account.domain.AccountStatusHistory;
import com.securebank.account.dto.AccountStatusHistoryResponse;
import com.securebank.account.repository.AccountStatusHistoryRepository;
import com.securebank.account.domain.AccountType;
import com.securebank.account.domain.CustomerAccountLock;
import com.securebank.account.dto.AccountBalanceResponse;
import com.securebank.account.dto.AccountResponse;
import com.securebank.account.dto.AccountStatusResponse;
import com.securebank.account.dto.CreateAccountRequest;
import com.securebank.account.dto.PageResponse;
import com.securebank.account.exception.ApiException;
import com.securebank.account.exception.ErrorCode;
import com.securebank.account.mapper.AccountMapper;
import com.securebank.account.repository.AccountRepository;
import com.securebank.account.repository.AccountSpecifications;
import com.securebank.account.repository.CustomerAccountLockRepository;
import com.securebank.account.security.CallerContext;
import java.time.Clock;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Business rules for accounts. Controllers call this class only; it is the sole user of the repository for
 * account use cases and always returns DTOs (never JPA entities).
 *
 * <p>Authorization rules enforced here (defence in depth next to the URL rules in SecurityConfig):
 * admins can see and manage every account; customers only accounts whose customerId equals their token's
 * customer id. A customer asking for somebody else's account gets 404 (existence is not revealed).
 */
@Service
public class AccountService {

    private static final Logger log = LoggerFactory.getLogger(AccountService.class);

    static final int MAX_ACCOUNT_NUMBER_ATTEMPTS = 5;
    static final int MAX_PAGE_SIZE = 100;
    static final String DEFAULT_CURRENCY = "INR";
    private static final Set<String> SUPPORTED_CURRENCIES = Set.of(DEFAULT_CURRENCY);
    private static final Sort DEFAULT_SORT = Sort.by(Sort.Order.desc("createdAt"), Sort.Order.asc("id"));

    private final AccountRepository accountRepository;
    private final CustomerClient customerClient;
    private final AccountNumberGenerator numberGenerator;
    private final TransactionTemplate transactionTemplate;
    private final CustomerAccountLockRepository lockRepository;
    private final AccountProperties properties;
    private final AccountStatusHistoryRepository historyRepository;
    private final Clock clock;

    public AccountService(AccountRepository accountRepository, CustomerClient customerClient,
                          AccountNumberGenerator numberGenerator, PlatformTransactionManager transactionManager,
                          CustomerAccountLockRepository lockRepository, AccountProperties properties,
                          AccountStatusHistoryRepository historyRepository, Clock clock) {
        this.accountRepository = accountRepository;
        this.customerClient = customerClient;
        this.numberGenerator = numberGenerator;
        this.transactionTemplate = new TransactionTemplate(transactionManager);
        this.lockRepository = lockRepository;
        this.properties = properties;
        this.historyRepository = historyRepository;
        this.clock = clock;
    }

    // ------------------------------------------------------------------ create

    /**
     * Opens an account. Deliberately NOT one big database transaction: the remote Customer Service call happens
     * before any database transaction is opened (no connection is held during network I/O), and each insert attempt
     * runs in its own short transaction so that an account-number collision can be retried cleanly.
     */
    public AccountResponse createAccount(CreateAccountRequest request, CallerContext caller) {
        // Ownership comes from the validated token, never from the body: a customer is bound to the customer_id
        // of their own token (a token without one cannot open accounts), an admin names the customer explicitly.
        if (caller.admin()) {
            // D2/D6: administrators never own bank accounts and cannot apply for one.
            throw new ApiException(ErrorCode.ACCESS_DENIED, "Administrators cannot apply for or own bank accounts.");
        }
        if (caller.customerId() == null) {
            throw new ApiException(ErrorCode.ACCESS_DENIED,
                    "Your identity is not linked to a customer, so you cannot apply for an account.");
        }
        if (!caller.canAccessCustomer(request.customerId())) {
            // Ownership privacy: someone else's customer id looks exactly like any unknown resource (404).
            throw resourceNotFound();
        }
        String currency = normalizeCurrency(request.currency());

        CustomerSummary customer = customerClient.findCustomer(request.customerId())
                .orElseThrow(() -> new ApiException(ErrorCode.CUSTOMER_NOT_FOUND,
                        "The customer does not exist."));
        if (!isEligible(customer)) {
            throw new ApiException(ErrorCode.CUSTOMER_NOT_ELIGIBLE,
                    "The customer is not eligible to open an account.");
        }

        Account created = insertWithUniqueAccountNumber(request.customerId(), request.accountType(), currency,
                caller);
        log.info("Account application {} submitted for customer {}", created.getId(), created.getCustomerId());
        return AccountMapper.toResponse(created);
    }

    private Account insertWithUniqueAccountNumber(UUID customerId, AccountType type, String currency,
                                                  CallerContext caller) {
        int maxOpen = properties.limits().maxOpenAccountsPerCustomer();
        ensureLockRow(customerId);
        for (int attempt = 1; attempt <= MAX_ACCOUNT_NUMBER_ATTEMPTS; attempt++) {
            String candidate = numberGenerator.generate();
            try {
                Account created = transactionTemplate.execute(status -> {
                    // FIRST statement of the transaction: take the customer's row lock, so the count below (a
                    // consistent read taken after the lock is granted) sees every account committed by earlier
                    // creations and no two creations for one customer can be between "count" and "insert" at once.
                    lockRepository.lockByCustomerId(customerId).orElseThrow(
                            () -> new IllegalStateException("Customer lock row is missing"));
                    if (accountRepository.existsByCustomerIdAndAccountTypeAndStatus(customerId, type,
                            AccountStatus.PENDING)) {
                        throw new ApiException(ErrorCode.APPLICATION_ALREADY_PENDING,
                                "You already have a PENDING " + type + " application.");
                    }
                    long open = accountRepository.countByCustomerIdAndStatusNotIn(customerId,
                            java.util.List.of(AccountStatus.CLOSED, AccountStatus.REJECTED));
                    if (open >= maxOpen) {
                        throw new ApiException(ErrorCode.ACCOUNT_LIMIT_EXCEEDED,
                                "The customer already has the maximum number of open accounts (" + maxOpen + ").");
                    }
                    if (accountRepository.existsByAccountNumber(candidate)) {
                        return null; // collision detected up-front: try another number
                    }
                    Account application = accountRepository.saveAndFlush(
                            Account.apply(customerId, candidate, type, currency, caller.subject(), clock.instant()));
                    recordHistory(application, null, caller);
                    return application;
                });
                if (created != null) {
                    return created;
                }
            } catch (DataIntegrityViolationException ex) {
                // The unique constraint caught a concurrent insert of the same number: retry with a new one.
                log.warn("Account number collision detected by the database (attempt {})", attempt);
            }
        }
        throw new ApiException(ErrorCode.ACCOUNT_NUMBER_UNAVAILABLE,
                "A unique account number could not be allocated. Please retry.");
    }

    /** Creates the customer's lock row if absent, in its own short transaction; a concurrent creator may win. */
    private void ensureLockRow(UUID customerId) {
        if (lockRepository.existsById(customerId)) {
            return;
        }
        try {
            transactionTemplate.executeWithoutResult(status ->
                    lockRepository.saveAndFlush(new CustomerAccountLock(customerId, clock.instant())));
        } catch (DataIntegrityViolationException alreadyCreatedConcurrently) {
            // another request created it first: exactly what we need
        }
    }

    private boolean isEligible(CustomerSummary customer) {
        return properties.customerService().eligibleStatuses().stream()
                .anyMatch(allowed -> allowed.equalsIgnoreCase(customer.status()));
    }

    private static String normalizeCurrency(String currency) {
        String effective = currency == null ? DEFAULT_CURRENCY : currency.toUpperCase(Locale.ROOT);
        if (!SUPPORTED_CURRENCIES.contains(effective)) {
            throw new ApiException(ErrorCode.UNSUPPORTED_CURRENCY,
                    "Unsupported currency. Supported currencies: " + SUPPORTED_CURRENCIES + ".");
        }
        return effective;
    }

    // ------------------------------------------------------------------ read

    @Transactional(readOnly = true)
    public AccountResponse getAccount(UUID accountId, CallerContext caller) {
        return AccountMapper.toResponse(loadAuthorized(accountId, caller));
    }

    @Transactional(readOnly = true)
    public AccountResponse getByAccountNumber(String accountNumber, CallerContext caller) {
        if (accountNumber == null || !accountNumber.matches("\\d{" + SecureAccountNumberGenerator.LENGTH + "}")) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED, "The account number must be 13 digits.");
        }
        Account account = accountRepository.findByAccountNumber(accountNumber)
                .filter(found -> caller.canAccessCustomer(found.getCustomerId()))
                .orElseThrow(AccountService::notFound);
        return AccountMapper.toResponse(account);
    }

    @Transactional(readOnly = true)
    public PageResponse<AccountResponse> listForCustomer(UUID customerId, AccountStatus status, int page, int size,
                                                         CallerContext caller) {
        if (caller.customerId() == null && !caller.admin()) {
            throw new ApiException(ErrorCode.ACCESS_DENIED,
                    "Your identity is not linked to a customer, so you cannot list accounts.");
        }
        if (!caller.canAccessCustomer(customerId)) {
            throw resourceNotFound();   // another customer's id: same 404 as any unknown resource
        }
        Specification<Account> spec = Specification.allOf(
                AccountSpecifications.hasCustomerId(customerId),
                AccountSpecifications.hasStatus(status));
        Page<Account> result = accountRepository.findAll(spec, pageable(page, size));
        return PageResponse.from(result, AccountMapper::toResponse);
    }

    @Transactional(readOnly = true)
    public PageResponse<AccountResponse> listAll(UUID customerId, AccountStatus status, AccountType type, int page,
                                                 int size, CallerContext caller) {
        requireAdmin(caller);
        Specification<Account> spec = Specification.allOf(
                AccountSpecifications.hasCustomerId(customerId),
                AccountSpecifications.hasStatus(status),
                AccountSpecifications.hasType(type));
        Page<Account> result = accountRepository.findAll(spec, pageable(page, size));
        return PageResponse.from(result, AccountMapper::toResponse);
    }

    @Transactional(readOnly = true)
    public AccountStatusResponse getStatus(UUID accountId, CallerContext caller) {
        return AccountMapper.toStatusResponse(loadAuthorized(accountId, caller));
    }

    @Transactional(readOnly = true)
    public AccountBalanceResponse getBalance(UUID accountId, CallerContext caller) {
        return AccountMapper.toBalanceResponse(loadAuthorized(accountId, caller));
    }

    // ------------------------------------------------------------------ status changes

    @Transactional
    public AccountStatusResponse freeze(UUID accountId, String reason, CallerContext caller) {
        requireAdmin(caller);
        Account account = loadAccount(accountId);
        AccountStatus previous = account.getStatus();
        account.freeze(reason, caller.subject(), clock.instant());
        Account saved = accountRepository.saveAndFlush(account);
        recordHistory(saved, previous, caller);
        log.info("Account {} frozen by {}", accountId, caller.subject());
        return AccountMapper.toStatusResponse(saved);
    }

    @Transactional
    public AccountStatusResponse unfreeze(UUID accountId, String reason, CallerContext caller) {
        requireAdmin(caller);
        Account account = loadAccount(accountId);
        AccountStatus previous = account.getStatus();
        account.unfreeze(reason, caller.subject(), clock.instant());
        Account saved = accountRepository.saveAndFlush(account);
        recordHistory(saved, previous, caller);
        log.info("Account {} unfrozen by {}", accountId, caller.subject());
        return AccountMapper.toStatusResponse(saved);
    }

    /** D6: PENDING -> ACTIVE. ADMIN only (re-checked here), reason mandatory, history/audit row in the same tx. */
    @Transactional
    public AccountStatusResponse approve(UUID accountId, String reason, CallerContext caller) {
        requireAdmin(caller);
        Account account = loadAccount(accountId);
        AccountStatus previous = account.getStatus();
        account.approve(reason, caller.subject(), clock.instant());
        Account saved = accountRepository.saveAndFlush(account);
        recordHistory(saved, previous, caller);
        log.info("Account application {} approved by {}", accountId, caller.subject());
        return AccountMapper.toStatusResponse(saved);
    }

    /** D6: PENDING -> REJECTED (terminal for the application). ADMIN only, reason mandatory, audited. */
    @Transactional
    public AccountStatusResponse reject(UUID accountId, String reason, CallerContext caller) {
        requireAdmin(caller);
        Account account = loadAccount(accountId);
        AccountStatus previous = account.getStatus();
        account.reject(reason, caller.subject(), clock.instant());
        Account saved = accountRepository.saveAndFlush(account);
        recordHistory(saved, previous, caller);
        log.info("Account application {} rejected by {}", accountId, caller.subject());
        return AccountMapper.toStatusResponse(saved);
    }

    /** Full status history of an account: its owner or an administrator (others get 404). */
    @Transactional(readOnly = true)
    public java.util.List<AccountStatusHistoryResponse> statusHistory(UUID accountId, CallerContext caller) {
        loadAuthorized(accountId, caller);
        return historyRepository.findByAccountIdOrderByIdAsc(accountId).stream()
                .map(h -> new AccountStatusHistoryResponse(h.getPreviousStatus(), h.getNewStatus(), h.getReason(),
                        h.getActorRole(), caller.admin() ? h.getActorSubject() : null, h.getOccurredAt()))
                .toList();
    }

    private void recordHistory(Account account, AccountStatus previous, CallerContext caller) {
        historyRepository.save(new AccountStatusHistory(account.getId(), previous, account.getStatus(),
                account.getStatusReason(), caller.subject(), caller.admin() ? "ADMIN" : "CUSTOMER",
                org.slf4j.MDC.get(com.securebank.account.config.CorrelationIdFilter.MDC_KEY), clock.instant()));
    }

    /**
     * Closes an account (owner or admin) if every closure condition holds: status ACTIVE and balance exactly zero.
     * A concurrent credit/debit makes one of the two writers fail on the version check, so a closure can never
     * race with a balance change.
     */
    @Transactional
    public AccountStatusResponse close(UUID accountId, CallerContext caller) {
        Account account = loadAuthorized(accountId, caller);
        AccountStatus previous = account.getStatus();
        account.close(caller.subject(), clock.instant());
        Account saved = accountRepository.saveAndFlush(account);
        recordHistory(saved, previous, caller);
        log.info("Account {} closed by {}", accountId, caller.subject());
        return AccountMapper.toStatusResponse(saved);
    }

    // ------------------------------------------------------------------ helpers

    private Account loadAuthorized(UUID accountId, CallerContext caller) {
        return accountRepository.findById(accountId)
                .filter(found -> caller.canAccessCustomer(found.getCustomerId()))
                .orElseThrow(AccountService::notFound);
    }

    private Account loadAccount(UUID accountId) {
        return accountRepository.findById(accountId).orElseThrow(AccountService::notFound);
    }

    private static void requireAdmin(CallerContext caller) {
        if (!caller.admin()) {
            throw new ApiException(ErrorCode.ACCESS_DENIED, "Administrator role required.");
        }
    }

    private static Pageable pageable(int page, int size) {
        if (page < 0) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED, "page must be 0 or greater.");
        }
        if (size < 1 || size > MAX_PAGE_SIZE) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED, "size must be between 1 and " + MAX_PAGE_SIZE + ".");
        }
        return PageRequest.of(page, size, DEFAULT_SORT);
    }

    private static ApiException resourceNotFound() {
        return new ApiException(ErrorCode.RESOURCE_NOT_FOUND, "Resource not found.");
    }

    private static ApiException notFound() {
        return new ApiException(ErrorCode.ACCOUNT_NOT_FOUND, "Account not found.");
    }
}
