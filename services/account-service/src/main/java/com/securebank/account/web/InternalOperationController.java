package com.securebank.account.web;

import com.securebank.account.dto.InternalAccountView;
import com.securebank.account.dto.InternalOperationRequest;
import com.securebank.account.dto.InternalOperationResponse;
import com.securebank.account.security.CallerResolver;
import com.securebank.account.service.BalanceOperationService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Canonical Transaction Service contract (ROLE_TRANSACTION_SERVICE only, via the {@code /internal/**} rule).
 * Must NOT be exposed through the API Gateway.
 */
@RestController
@RequestMapping("/internal/accounts")
@Tag(name = "Internal (Transaction Service only)")
public class InternalOperationController {

    private final BalanceOperationService service;
    private final CallerResolver callerResolver;

    public InternalOperationController(BalanceOperationService service, CallerResolver callerResolver) {
        this.service = service;
        this.callerResolver = callerResolver;
    }

    @GetMapping("/{accountId}")
    @Operation(summary = "Minimal account view for transfer validation (no balance)")
    public InternalAccountView account(@PathVariable UUID accountId) {
        return service.findAccountView(accountId);
    }

    @PostMapping("/{accountId}/operations")
    @Operation(summary = "Idempotently DEBIT, CREDIT or REVERSE_DEBIT (201 = applied, 200 = replay)")
    public ResponseEntity<InternalOperationResponse> apply(@PathVariable UUID accountId,
                                                           @Valid @RequestBody InternalOperationRequest request) {
        InternalOperationResponse response = service.applyOperation(accountId, request,
                callerResolver.currentSubject());
        HttpStatus status = response.replayed() ? HttpStatus.OK : HttpStatus.CREATED;
        return ResponseEntity.status(status).header("Idempotent-Replay", String.valueOf(response.replayed()))
                .body(response);
    }

    @GetMapping("/{accountId}/operations/{operationId}")
    @Operation(summary = "Status lookup after an ambiguous outcome (404 = never recorded)")
    public InternalOperationResponse operation(@PathVariable UUID accountId, @PathVariable UUID operationId) {
        return service.findOperation(accountId, operationId);
    }
}
