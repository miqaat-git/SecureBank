package com.securebank.ledger.controller;

import com.securebank.ledger.dto.CreateJournalEntryRequest;
import com.securebank.ledger.dto.JournalEntryResponse;
import com.securebank.ledger.dto.JournalEntrySummaryResponse;
import com.securebank.ledger.dto.JournalLineResponse;
import com.securebank.ledger.dto.PageResponse;
import com.securebank.ledger.dto.ReverseEntryRequest;
import com.securebank.ledger.enums.EntryStatus;
import com.securebank.ledger.service.JournalEntryService;
import com.securebank.ledger.service.PostingResult;
import jakarta.validation.Valid;
import java.net.URI;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.support.ServletUriComponentsBuilder;

@RestController
@Tag(name = "Journal entries")
@RequestMapping("/api/v1/journal-entries")
public class JournalEntryController {

    static final String REPLAY_HEADER = "Idempotent-Replay";

    private final JournalEntryService service;

    public JournalEntryController(JournalEntryService service) {
        this.service = service;
    }

    /** 201 when a new entry was posted, 200 (+ Idempotent-Replay: true) when an equivalent request was replayed. */
    @Operation(summary = "[ledger-writer] Post a balanced journal entry (idempotent on businessReference)")
    @PostMapping
    public ResponseEntity<JournalEntryResponse> create(@Valid @RequestBody CreateJournalEntryRequest request,
                                                       Authentication authentication) {
        return toResponse(service.post(request, actor(authentication)));
    }

    @Operation(summary = "Get a journal entry by id")
    @GetMapping("/{id}")
    public JournalEntryResponse getById(@PathVariable UUID id) {
        return service.getById(id);
    }

    @Operation(summary = "Get a journal entry by business reference")
    @GetMapping("/by-reference/{businessReference}")
    public JournalEntryResponse getByReference(@PathVariable String businessReference) {
        return service.getByBusinessReference(businessReference);
    }

    @Operation(summary = "List journal entries (paginated, filterable)")
    @GetMapping
    public PageResponse<JournalEntrySummaryResponse> list(
            @RequestParam(required = false) EntryStatus status,
            @RequestParam(required = false) String reference,
            @RequestParam(required = false) Instant from,
            @RequestParam(required = false) Instant to,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        return service.list(status, reference, from, to, page, size);
    }

    @Operation(summary = "Lines of one journal entry")
    @GetMapping("/{id}/lines")
    public List<JournalLineResponse> getLines(@PathVariable UUID id) {
        return service.getLines(id);
    }

    /** Creates the compensating entry. Idempotent per original entry; at most one reversal ever exists. */
    @Operation(summary = "[ledger-reverser] Reverse a journal entry with a new compensating entry (the original is never edited)")
    @PostMapping("/{id}/reversals")
    public ResponseEntity<JournalEntryResponse> reverse(@PathVariable UUID id,
                                                        @Valid @RequestBody ReverseEntryRequest request,
                                                        Authentication authentication) {
        return toResponse(service.reverse(id, request, actor(authentication)));
    }

    private static ResponseEntity<JournalEntryResponse> toResponse(PostingResult result) {
        if (result.created()) {
            URI location = ServletUriComponentsBuilder.fromCurrentContextPath()
                    .path("/api/v1/journal-entries/{id}")
                    .buildAndExpand(result.entry().id())
                    .toUri();
            return ResponseEntity.created(location).body(result.entry());
        }
        return ResponseEntity.ok().header(REPLAY_HEADER, "true").body(result.entry());
    }

    private static String actor(Authentication authentication) {
        return authentication == null || authentication.getName() == null ? "unknown" : authentication.getName();
    }
}
