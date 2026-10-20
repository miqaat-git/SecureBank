package com.securebank.account.web;

import com.securebank.account.domain.AccountStatus;
import com.securebank.account.dto.AccountBalanceResponse;
import com.securebank.account.dto.AccountResponse;
import com.securebank.account.dto.AccountStatusResponse;
import com.securebank.account.dto.CreateAccountRequest;
import com.securebank.account.dto.PageResponse;
import com.securebank.account.security.CallerResolver;
import com.securebank.account.service.AccountService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.net.URI;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Customer- and admin-facing account API. There is intentionally no endpoint that writes a balance: balances only
 * change through the internal, idempotent operations used by the Transaction Service.
 */
@RestController
@RequestMapping("/api/v1")
@Tag(name = "Accounts")
public class AccountController {

    private final AccountService accountService;
    private final CallerResolver callerResolver;

    public AccountController(AccountService accountService, CallerResolver callerResolver) {
        this.accountService = accountService;
        this.callerResolver = callerResolver;
    }

    @PostMapping("/accounts")
    @Operation(summary = "Open an account for an existing, eligible customer (zero opening balance)")
    public ResponseEntity<AccountResponse> create(@Valid @RequestBody CreateAccountRequest request) {
        AccountResponse created = accountService.createAccount(request, callerResolver.current());
        return ResponseEntity.created(URI.create("/api/v1/accounts/" + created.id())).body(created);
    }

    @GetMapping("/accounts/{accountId}")
    @Operation(summary = "Get an account by id")
    public AccountResponse getById(@PathVariable UUID accountId) {
        return accountService.getAccount(accountId, callerResolver.current());
    }

    @GetMapping("/account-numbers/{accountNumber}")
    @Operation(summary = "Get an account by its 13-digit account number")
    public AccountResponse getByAccountNumber(@PathVariable String accountNumber) {
        return accountService.getByAccountNumber(accountNumber, callerResolver.current());
    }

    @GetMapping("/accounts")
    @Operation(summary = "List a customer's accounts (paginated, newest first)")
    public PageResponse<AccountResponse> listForCustomer(
            @RequestParam UUID customerId,
            @RequestParam(required = false) AccountStatus status,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        return accountService.listForCustomer(customerId, status, page, size, callerResolver.current());
    }

    @GetMapping("/accounts/{accountId}/status")
    @Operation(summary = "Get the status of an account")
    public AccountStatusResponse getStatus(@PathVariable UUID accountId) {
        return accountService.getStatus(accountId, callerResolver.current());
    }

    @GetMapping("/accounts/{accountId}/balance")
    @Operation(summary = "Get the available balance of an account (read-only)")
    public AccountBalanceResponse getBalance(@PathVariable UUID accountId) {
        return accountService.getBalance(accountId, callerResolver.current());
    }

    @PostMapping("/accounts/{accountId}/close")
    @Operation(summary = "Close an account (owner or admin) - only ACTIVE accounts with a zero balance")
    public AccountStatusResponse close(@PathVariable UUID accountId) {
        return accountService.close(accountId, callerResolver.current());
    }
}
