package com.securebank.transaction.web;

import com.securebank.transaction.security.CallerResolver;
import com.securebank.transaction.service.ReconciliationReportService;
import com.securebank.transaction.web.dto.ReconciliationReport;
import com.securebank.transaction.web.dto.ReconciliationResult;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.UUID;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Administrator-only, strictly read-only Account-to-Ledger balance reconciliation view. */
@RestController
@RequestMapping("/api/v1/admin/reconciliation")
@Tag(name = "Admin reconciliation")
public class AdminReconciliationController {

    private final ReconciliationReportService service;
    private final CallerResolver callers;

    public AdminReconciliationController(ReconciliationReportService service, CallerResolver callers) {
        this.service = service;
        this.callers = callers;
    }

    @Operation(summary = "[Admin] Compare Account Service balances with ledger-derived balances (read-only)",
            description = "ADMIN only. MATCH / DISCREPANCY / INCOMPLETE per account. Nothing is corrected or posted. "
                    + "503 with ACCOUNT_SERVICE_UNAVAILABLE or LEDGER_SERVICE_UNAVAILABLE when a source cannot be read.")
    @GetMapping("/account-balances")
    public ReconciliationReport accountBalances(
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String currency,
            @RequestParam(required = false) UUID accountId,
            @RequestParam(required = false) ReconciliationResult result,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "50") int size,
            Authentication authentication) {
        return service.report(callers.resolve(authentication), status, currency, accountId, result, page, size);
    }
}
