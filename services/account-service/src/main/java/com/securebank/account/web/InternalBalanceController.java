package com.securebank.account.web;

import com.securebank.account.dto.BalanceOperationRequest;
import com.securebank.account.dto.BalanceOperationResponse;
import com.securebank.account.security.CallerResolver;
import com.securebank.account.service.BalanceOperationService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Service-to-service API for the Transaction Service only (ROLE_TRANSACTION_SERVICE). Must NOT be exposed through
 * the API Gateway. Customer and admin tokens receive 403.
 */
@RestController
@RequestMapping("/internal/v1/accounts")
@Tag(name = "Internal (Transaction Service only)")
public class InternalBalanceController {

    private final BalanceOperationService balanceOperationService;
    private final CallerResolver callerResolver;

    public InternalBalanceController(BalanceOperationService balanceOperationService,
                                     CallerResolver callerResolver) {
        this.balanceOperationService = balanceOperationService;
        this.callerResolver = callerResolver;
    }

    @PostMapping("/{accountId}/balance-operations")
    @Operation(summary = "Idempotently debit or credit an account balance (201 = applied, 200 = replay)")
    public ResponseEntity<BalanceOperationResponse> apply(@PathVariable UUID accountId,
                                                          @Valid @RequestBody BalanceOperationRequest request) {
        BalanceOperationResponse response =
                balanceOperationService.apply(accountId, request, callerResolver.currentSubject());
        HttpStatus status = response.replayed() ? HttpStatus.OK : HttpStatus.CREATED;
        return ResponseEntity.status(status).header("Idempotent-Replay", String.valueOf(response.replayed()))
                .body(response);
    }
}
