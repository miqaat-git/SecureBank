package com.securebank.account.service;

import com.securebank.account.client.CustomerClient;
import com.securebank.account.client.CustomerSummary;
import com.securebank.account.config.AccountProperties;
import com.securebank.account.domain.Account;
import com.securebank.account.domain.AccountStatus;
import com.securebank.account.domain.AccountType;
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
    private final AccountProperties properties;
    private final Clock clock;

    public AccountService(AccountRepository accountRepository, CustomerClient customerClient,
                          AccountNumberGenerator numberGenerator, PlatformTransactionManager transactionManager,
                          AccountProperties properties, Clock clock) {
        this.accountRepository = accountRepository;
        this.customerClient = customerClient;
        this.numberGenerator = numberGenerator;
        this.transactionTemplate = new TransactionTemplate(transactionManager);
        this.properties = properties;
        this.clock = clock;
    }

    // ------------------------------------------------------------------ create

    /**
     * Opens an account. Deliberately NOT one big database transaction: the remote Customer Service call happens
     * before any database transaction is opened (no connection is held during network I/O), and each insert attempt
     * runs in its own short transaction so that an account-number collision can be retried cleanly.
     */
    public AccountResponse createAccount(CreateAccountRequest request, CallerContext caller) {
        if (!caller.canAccessCustomer(request.customerId())) {
            throw new ApiException(ErrorCode.ACCESS_DENIED, "You may only open accounts for your own customer id.");
        }
        String currency = normalizeCurrency(request.currency());

        CustomerSummary customer = customerClient.findCustomer(request.customerId())
                .orElseThrow(() -> new ApiException(ErrorCode.CUSTOMER_NOT_FOUND,
                        "The customer does not exist."));
        if (!isEligible(customer)) {
            throw new ApiException(ErrorCode.CUSTOMER_NOT_ELIGIBLE,
                    "The customer is not eligible to open an account.");
        }

        Account created = insertWithUniqueAccountNumber(request.customerId(), request.accountType(), currency);
        log.info("Account {} opened for customer {}", created.getId(), created.getCustomerId());
        return AccountMapper.toResponse(created);
    }

    private Account insertWithUniqueAccountNumber(UUID customerId, AccountType type, String currency) {
        int maxOpen = properties.limits().maxOpenAccountsPerCustomer();
        for (int attempt = 1; attempt <= MAX_ACCOUNT_NUMBER_ATTEMPTS; attempt++) {
            String candidate = numberGenerator.generate();
            try {
                Account created = transactionTemplate.execute(status -> {
                    long open = accountRepository.countByCustomerIdAndStatusNot(customerId, AccountStatus.CLOSED);
                    if (open >= maxOpen) {
                        throw new ApiException(ErrorCode.ACCOUNT_LIMIT_EXCEEDED,
                                "The customer already has the maximum number of open accounts (" + maxOpen + ").");
                    }
                    if (accountRepository.existsByAccountNumber(candidate)) {
                        return null; // collision detected up-front: try another number
                    }
                    return accountRepository.saveAndFlush(
                            Account.open(customerId, candidate, type, currency, clock.instant()));
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
        if (!caller.canAccessCustomer(customerId)) {
            throw new ApiException(ErrorCode.ACCESS_DENIED, "You may only list your own accounts.");
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
        account.freeze(reason, caller.subject(), clock.instant());
        Account saved = accountRepository.saveAndFlush(account);
        log.info("Account {} frozen by {}", accountId, caller.subject());
        return AccountMapper.toStatusResponse(saved);
    }

    @Transactional
    public AccountStatusResponse unfreeze(UUID accountId, String reason, CallerContext caller) {
        requireAdmin(caller);
        Account account = loadAccount(accountId);
        account.unfreeze(reason, caller.subject(), clock.instant());
        Account saved = accountRepository.saveAndFlush(account);
        log.info("Account {} unfrozen by {}", accountId, caller.subject());
        return AccountMapper.toStatusResponse(saved);
    }

    /**
     * Closes an account (owner or admin) if every closure condition holds: status ACTIVE and balance exactly zero.
     * A concurrent credit/debit makes one of the two writers fail on the version check, so a closure can never
     * race with a balance change.
     */
    @Transactional
    public AccountStatusResponse close(UUID accountId, CallerContext caller) {
        Account account = loadAuthorized(accountId, caller);
        account.close(caller.subject(), clock.instant());
        Account saved = accountRepository.saveAndFlush(account);
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

    private static ApiException notFound() {
        return new ApiException(ErrorCode.ACCOUNT_NOT_FOUND, "Account not found.");
    }
}
