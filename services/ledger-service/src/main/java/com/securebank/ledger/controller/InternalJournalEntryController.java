package com.securebank.ledger.controller;

import com.securebank.ledger.dto.CreateJournalEntryRequest;
import com.securebank.ledger.dto.InternalPostRequest;
import com.securebank.ledger.dto.InternalReversalRequest;
import com.securebank.ledger.dto.JournalEntryResponse;
import com.securebank.ledger.dto.ReverseEntryRequest;
import com.securebank.ledger.service.InternalJournalTranslator;
import com.securebank.ledger.service.JournalEntryService;
import com.securebank.ledger.service.PostingResult;
import jakarta.validation.Valid;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Integration boundary for Transaction Service. It only translates the wire format and delegates to the same
 * {@link JournalEntryService} as the public API; it contains no posting or reversal logic of its own.
 * Must NOT be routed through the API Gateway. See SecurityConfig for the required roles.
 */
@RestController
@Tag(name = "Internal (Transaction Service only; not routed by the Gateway)")
@RequestMapping("/internal/journal-entries")
public class InternalJournalEntryController {

    private final JournalEntryService service;

    public InternalJournalEntryController(JournalEntryService service) {
        this.service = service;
    }

    /** 201 when posted, 200 + Idempotent-Replay: true for an equivalent replay, 409 for a conflicting reuse. */
    @Operation(summary = "[Internal, ledger-writer] Post the balanced journal entry for a transfer (idempotent on businessReference)")
    @PostMapping
    public ResponseEntity<JournalEntryResponse> post(@Valid @RequestBody InternalPostRequest request,
                                                     Authentication authentication) {
        CreateJournalEntryRequest canonical = InternalJournalTranslator.toCreateRequest(request);
        return toResponse(service.post(canonical, actor(authentication)));
    }

    /** 200 with the stored entry, or the standard 404 when no entry has that (normalised) reference. */
    @Operation(summary = "[Internal, any ledger role] Look up a posting by business reference (used to resolve an UNKNOWN outcome)")
    @GetMapping("/by-reference/{businessReference}")
    public JournalEntryResponse getByReference(@PathVariable String businessReference) {
        return service.getByBusinessReference(InternalJournalTranslator.normalizeReference(businessReference));
    }

    /**
     * The path names the ORIGINAL business reference. It is resolved to the entry id, then the existing reversal
     * logic runs (one reversal per original, fingerprint-checked replays, unique reversal reference).
     */
    @Operation(summary = "[Internal, ledger-reverser] Reverse a posting by business reference (compensation)")
    @PostMapping("/{businessReference}/reversals")
    public ResponseEntity<JournalEntryResponse> reverse(@PathVariable String businessReference,
                                                        @Valid @RequestBody InternalReversalRequest request,
                                                        Authentication authentication) {
        JournalEntryResponse original = service.getByBusinessReference(
                InternalJournalTranslator.normalizeReference(businessReference));
        ReverseEntryRequest canonical = InternalJournalTranslator.toReverseRequest(request);
        return toResponse(service.reverse(original.id(), canonical, actor(authentication)));
    }

    private static ResponseEntity<JournalEntryResponse> toResponse(PostingResult result) {
        if (result.created()) {
            return ResponseEntity.status(201).body(result.entry());
        }
        return ResponseEntity.ok().header(JournalEntryController.REPLAY_HEADER, "true").body(result.entry());
    }

    private static String actor(Authentication authentication) {
        return authentication == null || authentication.getName() == null ? "unknown" : authentication.getName();
    }
}
