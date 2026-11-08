package com.securebank.ledger.controller;

import com.securebank.ledger.dto.LedgerBalancesResponse;
import com.securebank.ledger.service.LedgerBalancesService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Read-only reconciliation support for the Transaction Service. Must NOT be routed through the Gateway. */
@RestController
@Tag(name = "Internal (Transaction Service only; not routed by the Gateway)")
@RequestMapping("/internal/ledger-balances")
public class InternalLedgerBalancesController {

    private final LedgerBalancesService service;

    public InternalLedgerBalancesController(LedgerBalancesService service) {
        this.service = service;
    }

    @Operation(summary = "[Internal, any ledger role] Posted debit/credit totals for up to 100 account ids")
    @GetMapping
    public LedgerBalancesResponse balances(@RequestParam("accountIds") List<String> accountIds) {
        return service.balances(accountIds);
    }
}
