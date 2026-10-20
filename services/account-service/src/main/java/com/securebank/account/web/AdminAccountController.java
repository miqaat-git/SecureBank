package com.securebank.account.web;

import com.securebank.account.domain.AccountStatus;
import com.securebank.account.domain.AccountType;
import com.securebank.account.dto.AccountResponse;
import com.securebank.account.dto.AccountStatusResponse;
import com.securebank.account.dto.PageResponse;
import com.securebank.account.dto.StatusChangeRequest;
import com.securebank.account.security.CallerResolver;
import com.securebank.account.service.AccountService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.util.UUID;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Administrative operations. Requires ROLE_ADMIN (URL rule + service-layer check). */
@RestController
@RequestMapping("/api/v1/admin/accounts")
@Tag(name = "Administration")
public class AdminAccountController {

    private final AccountService accountService;
    private final CallerResolver callerResolver;

    public AdminAccountController(AccountService accountService, CallerResolver callerResolver) {
        this.accountService = accountService;
        this.callerResolver = callerResolver;
    }

    @GetMapping
    @Operation(summary = "Search accounts with optional filters (paginated, newest first)")
    public PageResponse<AccountResponse> search(
            @RequestParam(required = false) UUID customerId,
            @RequestParam(required = false) AccountStatus status,
            @RequestParam(required = false) AccountType accountType,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        return accountService.listAll(customerId, status, accountType, page, size, callerResolver.current());
    }

    @PostMapping("/{accountId}/freeze")
    @Operation(summary = "Freeze an ACTIVE account (blocks all balance operations)")
    public AccountStatusResponse freeze(@PathVariable UUID accountId,
                                        @Valid @RequestBody StatusChangeRequest request) {
        return accountService.freeze(accountId, request.reason(), callerResolver.current());
    }

    @PostMapping("/{accountId}/unfreeze")
    @Operation(summary = "Unfreeze a FROZEN account")
    public AccountStatusResponse unfreeze(@PathVariable UUID accountId,
                                          @Valid @RequestBody StatusChangeRequest request) {
        return accountService.unfreeze(accountId, request.reason(), callerResolver.current());
    }
}
