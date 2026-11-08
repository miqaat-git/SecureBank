package com.securebank.account.web;

import com.securebank.account.domain.AccountStatus;
import com.securebank.account.dto.InternalBalanceView;
import com.securebank.account.dto.PageResponse;
import com.securebank.account.service.InternalBalanceQueryService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.UUID;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Read-only reconciliation support (ROLE_TRANSACTION_SERVICE via the {@code /internal/**} rule). Not routed by the
 * Gateway. Exposes no customer data and has no write operation.
 */
@RestController
@RequestMapping("/internal/account-balances")
@Tag(name = "Internal (Transaction Service only)")
public class InternalAccountBalancesController {

    private final InternalBalanceQueryService service;

    public InternalAccountBalancesController(InternalBalanceQueryService service) {
        this.service = service;
    }

    @GetMapping
    @Operation(summary = "Paged account balances ordered by account id (read-only)")
    public PageResponse<InternalBalanceView> list(@RequestParam(required = false) AccountStatus status,
                                                  @RequestParam(required = false) String currency,
                                                  @RequestParam(required = false) UUID accountId,
                                                  @RequestParam(defaultValue = "0") int page,
                                                  @RequestParam(defaultValue = "50") int size) {
        return service.list(status, currency, accountId, page, size);
    }
}
