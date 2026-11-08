package com.securebank.transaction.web;

import com.securebank.transaction.security.CallerResolver;
import com.securebank.transaction.service.FundingCommand;
import com.securebank.transaction.service.InitiationResult;
import com.securebank.transaction.service.TransferService;
import com.securebank.transaction.web.dto.FundingRequest;
import com.securebank.transaction.web.dto.TransferResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Administrator-only simulated funding (Blueprint D7). There is no customer-facing funding endpoint, no deposit or
 * withdrawal, and no external source of money.
 */
@RestController
@RequestMapping("/api/v1/admin/funding")
@Tag(name = "Admin funding")
public class AdminFundingController {

    private final TransferService transfers;
    private final CallerResolver callers;

    public AdminFundingController(TransferService transfers, CallerResolver callers) {
        this.transfers = transfers;
        this.callers = callers;
    }

    @Operation(summary = "[Admin] Fund a customer account with simulated funds",
            description = "ADMIN role only. Requires header Idempotency-Key, a unique fundingReference and a reason. "
                    + "The target must be an ACTIVE customer account not owned by the administrator. 201/202 = new; "
                    + "200 = replay of the same key and request; 409 = same key with a different request or a "
                    + "reused fundingReference. Funding records are immutable and cannot be reversed in v1.")
    @PostMapping
    public ResponseEntity<TransferResponse> fund(
            @RequestHeader(name = "Idempotency-Key") String idempotencyKey,
            @Valid @RequestBody FundingRequest request,
            Authentication authentication) {
        InitiationResult result = transfers.initiateFunding(idempotencyKey,
                new FundingCommand(request.targetAccountId(), request.amount(), request.currency(),
                        request.fundingReference(), request.reason()),
                callers.resolve(authentication));
        return TransferController.respond(result);
    }
}
