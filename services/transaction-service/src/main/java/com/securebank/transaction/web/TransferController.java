package com.securebank.transaction.web;

import com.securebank.transaction.domain.Transfer;
import com.securebank.transaction.domain.TransferStatus;
import com.securebank.transaction.security.Caller;
import com.securebank.transaction.security.CallerResolver;
import com.securebank.transaction.service.InitiationResult;
import com.securebank.transaction.service.TransferCommand;
import com.securebank.transaction.service.TransferQueryService;
import com.securebank.transaction.service.TransferSearchCriteria;
import com.securebank.transaction.service.TransferService;
import com.securebank.transaction.web.dto.OperationResponse;
import com.securebank.transaction.web.dto.PageResponse;
import com.securebank.transaction.web.dto.TransferHistoryResponse;
import com.securebank.transaction.web.dto.TransferRequest;
import com.securebank.transaction.web.dto.TransferResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.net.URI;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/transfers")
@Tag(name = "Transfers")
public class TransferController {

    private static final int MAX_PAGE_SIZE = 100;

    private final TransferService transfers;
    private final TransferQueryService queries;
    private final CallerResolver callers;

    public TransferController(TransferService transfers, TransferQueryService queries, CallerResolver callers) {
        this.transfers = transfers;
        this.queries = queries;
        this.callers = callers;
    }

    @Operation(summary = "Initiate a transfer",
            description = "Requires header Idempotency-Key. 201 = new and finished; 202 = new and still in progress; "
                    + "200 = replay of an existing key (no new workflow). 409 if the key was used with a different request.")
    @PostMapping
    public ResponseEntity<TransferResponse> initiate(
            @RequestHeader(name = "Idempotency-Key") String idempotencyKey,
            @Valid @RequestBody TransferRequest request,
            Authentication authentication) {
        Caller caller = callers.resolve(authentication);
        InitiationResult result = transfers.initiate(idempotencyKey,
                new TransferCommand(request.sourceAccountId(), request.destinationAccountId(), request.amount(),
                        request.currency()), caller);
        Transfer t = result.transfer();
        URI location = URI.create("/api/v1/transfers/" + t.getId());
        ResponseEntity.BodyBuilder builder;
        if (result.replay()) {
            builder = ResponseEntity.ok().header("Idempotent-Replay", "true");
        } else if (t.getStatus().isTerminal()) {
            builder = ResponseEntity.status(HttpStatus.CREATED).location(location);
        } else {
            builder = ResponseEntity.status(HttpStatus.ACCEPTED).location(location);
        }
        return builder.body(TransferResponse.from(t));
    }

    @Operation(summary = "Get a transfer by id")
    @GetMapping("/{transferId}")
    public TransferResponse get(@PathVariable UUID transferId, Authentication authentication) {
        return TransferResponse.from(queries.get(transferId, callers.resolve(authentication)));
    }

    @Operation(summary = "Get a transfer by idempotency key")
    @GetMapping("/by-idempotency-key/{key}")
    public TransferResponse getByKey(@PathVariable String key, Authentication authentication) {
        return TransferResponse.from(queries.getByKey(key, callers.resolve(authentication)));
    }

    @Operation(summary = "List transfers (paginated, filterable). Customers see only their own transfers.")
    @GetMapping
    public PageResponse<TransferResponse> list(
            @RequestParam(required = false) TransferStatus status,
            @RequestParam(required = false) UUID sourceAccountId,
            @RequestParam(required = false) UUID destinationAccountId,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant createdFrom,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant createdTo,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size,
            Authentication authentication) {
        int safePage = Math.max(page, 0);
        int safeSize = Math.min(Math.max(size, 1), MAX_PAGE_SIZE);
        PageRequest pageable = PageRequest.of(safePage, safeSize,
                Sort.by(Sort.Order.desc("createdAt"), Sort.Order.asc("id")));
        return PageResponse.from(queries.search(
                new TransferSearchCriteria(status, sourceAccountId, destinationAccountId, createdFrom, createdTo),
                callers.resolve(authentication), pageable), TransferResponse::from);
    }

    @Operation(summary = "Persisted state-transition history of a transfer")
    @GetMapping("/{transferId}/history")
    public List<TransferHistoryResponse> history(@PathVariable UUID transferId, Authentication authentication) {
        return queries.history(transferId, callers.resolve(authentication)).stream()
                .map(TransferHistoryResponse::from).toList();
    }

    @Operation(summary = "[Operator] Persisted workflow steps of a transfer")
    @GetMapping("/{transferId}/operations")
    public List<OperationResponse> operations(@PathVariable UUID transferId, Authentication authentication) {
        return queries.operations(transferId, callers.resolve(authentication)).stream()
                .map(OperationResponse::from).toList();
    }

    @Operation(summary = "[Operator] Retry/resume an incomplete transfer",
            description = "Allowed for INITIATED, VALIDATING, PROCESSING, COMPENSATION_REQUIRED. 409 otherwise.")
    @PostMapping("/{transferId}/retry")
    public ResponseEntity<TransferResponse> retry(@PathVariable UUID transferId) {
        Transfer t = transfers.retry(transferId);
        return ResponseEntity.status(t.getStatus().isTerminal() ? HttpStatus.OK : HttpStatus.ACCEPTED)
                .body(TransferResponse.from(t));
    }

    @Operation(summary = "[Operator] Reconcile an ambiguous transfer",
            description = "Allowed only for RECONCILIATION_REQUIRED. Re-queries remote outcomes, then resumes or "
                    + "compensates. The returned status shows the result; unchanged status means inconclusive.")
    @PostMapping("/{transferId}/reconcile")
    public ResponseEntity<TransferResponse> reconcile(@PathVariable UUID transferId) {
        Transfer t = transfers.reconcile(transferId);
        return ResponseEntity.status(t.getStatus().isTerminal() ? HttpStatus.OK : HttpStatus.ACCEPTED)
                .body(TransferResponse.from(t));
    }
}
