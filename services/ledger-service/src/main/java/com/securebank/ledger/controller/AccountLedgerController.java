package com.securebank.ledger.controller;

import com.securebank.ledger.dto.AccountLedgerLineResponse;
import com.securebank.ledger.dto.AccountMovementResponse;
import com.securebank.ledger.dto.PageResponse;
import com.securebank.ledger.service.AccountLedgerService;
import java.time.Instant;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/accounts/{accountReference}")
public class AccountLedgerController {

    private final AccountLedgerService service;

    public AccountLedgerController(AccountLedgerService service) {
        this.service = service;
    }

    /** Ledger lines of one account, newest first. Date range is [from, to). */
    @GetMapping("/ledger-lines")
    public PageResponse<AccountLedgerLineResponse> ledgerLines(
            @PathVariable String accountReference,
            @RequestParam(required = false) Instant from,
            @RequestParam(required = false) Instant to,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        return service.getLines(accountReference, from, to, page, size);
    }

    /** Net ledger movement (debits minus credits) of posted lines. Accounting view, not an operational balance. */
    @GetMapping("/movement")
    public AccountMovementResponse movement(
            @PathVariable String accountReference,
            @RequestParam(required = false) String currency,
            @RequestParam(required = false) Instant from,
            @RequestParam(required = false) Instant to) {
        return service.getMovement(accountReference, currency, from, to);
    }
}
