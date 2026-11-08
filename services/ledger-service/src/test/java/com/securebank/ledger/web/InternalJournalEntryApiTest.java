package com.securebank.ledger.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.securebank.ledger.support.AbstractLedgerTest;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.JwtRequestPostProcessor;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.ResultActions;

/**
 * Internal adapter used by Transaction Service: wire-format mapping, idempotency, lookup, reversal by business
 * reference and role-based authorization (real security filter chain, isolated H2 database). The request bodies
 * mirror what Transaction Service's LedgerPostRequest / LedgerReversalRequest serialise to.
 */
class InternalJournalEntryApiTest extends AbstractLedgerTest {

    private static final String POST_PATH = "/internal/journal-entries";

    @Autowired
    private MockMvc mvc;

    @Autowired
    private ObjectMapper objectMapper;

    private final UUID source = UUID.randomUUID();
    private final UUID destination = UUID.randomUUID();
    private final String reference = "TRANSFER-" + UUID.randomUUID();      // lower-case UUID, as Transaction sends

    private static JwtRequestPostProcessor as(String... roles) {
        JwtRequestPostProcessor processor = jwt().jwt(token -> token.subject("transaction-service-account"));
        SimpleGrantedAuthority[] authorities = new SimpleGrantedAuthority[roles.length];
        for (int i = 0; i < roles.length; i++) {
            authorities[i] = new SimpleGrantedAuthority("ROLE_" + roles[i]);
        }
        return processor.authorities(authorities);
    }

    private String postBody(String businessReference, String debitAmount, String creditAmount) {
        return """
                {"businessReference":"%s","idempotencyKey":"%s","description":"Transfer",
                 "currency":"INR","entries":[
                  {"accountId":"%s","direction":"DEBIT","amount":%s},
                  {"accountId":"%s","direction":"CREDIT","amount":%s}]}
                """.formatted(businessReference, UUID.randomUUID(), source, debitAmount, destination, creditAmount);
    }

    private ResultActions postEntry(String json, JwtRequestPostProcessor caller) throws Exception {
        return mvc.perform(post(POST_PATH).with(caller).contentType(MediaType.APPLICATION_JSON).content(json));
    }

    private ResultActions reverse(String originalReference, String reversalReference, String reason,
                                  JwtRequestPostProcessor caller) throws Exception {
        String json = """
                {"reversalBusinessReference":"%s","idempotencyKey":"%s","reason":"%s"}
                """.formatted(reversalReference, UUID.randomUUID(), reason);
        return mvc.perform(post(POST_PATH + "/" + originalReference + "/reversals").with(caller)
                .contentType(MediaType.APPLICATION_JSON).content(json));
    }

    private JsonNode json(ResultActions actions) throws Exception {
        MvcResult result = actions.andReturn();
        return objectMapper.readTree(result.getResponse().getContentAsString());
    }

    // ------------------------------------------------------------ posting

    @Test
    void postsATransactionShapedRequestAsABalancedNormalisedEntry() throws Exception {
        JsonNode body = json(postEntry(postBody(reference, "250.75", "250.75"), as("LEDGER_WRITER"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("POSTED"))
                .andExpect(jsonPath("$.entryType").value("NORMAL"))
                .andExpect(jsonPath("$.businessReference").value(reference.toUpperCase()))
                .andExpect(jsonPath("$.lines.length()").value(2)));

        assertThat(body.get("lines").get(0).get("lineNumber").asInt()).isEqualTo(1);
        assertThat(body.get("lines").get(0).get("accountReference").asText())
                .isEqualTo(source.toString().toUpperCase());
        assertThat(body.get("lines").get(0).get("debitAmount").decimalValue()).isEqualByComparingTo("250.75");
        assertThat(body.get("lines").get(1).get("accountReference").asText())
                .isEqualTo(destination.toString().toUpperCase());
        assertThat(body.get("lines").get(1).get("creditAmount").decimalValue()).isEqualByComparingTo("250.75");
        assertThat(countEntries()).isEqualTo(1);
        assertThat(countLines()).isEqualTo(2);
    }

    @Test
    void replayingTheSameRequestReturnsTheStoredEntryWithoutASecondPosting() throws Exception {
        String id = json(postEntry(postBody(reference, "10.00", "10.00"), as("LEDGER_WRITER"))
                .andExpect(status().isCreated())).get("id").asText();

        postEntry(postBody(reference, "10.00", "10.00"), as("LEDGER_WRITER"))
                .andExpect(status().isOk())
                .andExpect(header().string("Idempotent-Replay", "true"))
                .andExpect(jsonPath("$.id").value(id));

        assertThat(countEntries()).isEqualTo(1);
        assertThat(countLines()).isEqualTo(2);
    }

    @Test
    void reusingAReferenceForADifferentRequestIsAConflict() throws Exception {
        postEntry(postBody(reference, "10.00", "10.00"), as("LEDGER_WRITER")).andExpect(status().isCreated());

        postEntry(postBody(reference, "11.00", "11.00"), as("LEDGER_WRITER"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("BUSINESS_REFERENCE_CONFLICT"));

        assertThat(countEntries()).isEqualTo(1);
    }

    @Test
    void ledgerValidationStillRejectsUnbalancedAndInvalidEntries() throws Exception {
        postEntry(postBody(reference, "10.00", "9.99"), as("LEDGER_WRITER"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("UNBALANCED_ENTRY"));
        postEntry(postBody(reference, "-5.00", "-5.00"), as("LEDGER_WRITER"))
                .andExpect(status().isUnprocessableEntity());
        postEntry(postBody(reference, "10.001", "10.001"), as("LEDGER_WRITER"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("PRECISION_EXCEEDED"));
        postEntry("""
                {"businessReference":"%s","idempotencyKey":"%s","description":"One leg","currency":"INR",
                 "entries":[{"accountId":"%s","direction":"DEBIT","amount":10.00}]}
                """.formatted(reference, UUID.randomUUID(), source), as("LEDGER_WRITER"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("TOO_FEW_LINES"));
        postEntry(postBody(reference, "10.00", "10.00").replace("\"INR\"", "\"USD\""), as("LEDGER_WRITER"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("UNSUPPORTED_CURRENCY"));

        assertThat(countEntries()).isZero();
    }

    @Test
    void malformedRequestsAreRejectedAsBadRequests() throws Exception {
        postEntry(postBody(reference, "10.00", "10.00").replace("\"DEBIT\"", "\"SIDEWAYS\""), as("LEDGER_WRITER"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
        postEntry(postBody(reference, "10.00", "10.00").replace("\"DEBIT\"", "\"debit\""), as("LEDGER_WRITER"))
                .andExpect(status().isBadRequest());
        postEntry(postBody("x", "10.00", "10.00"), as("LEDGER_WRITER")).andExpect(status().isBadRequest());
        postEntry("{\"businessReference\":\"" + reference + "\"}", as("LEDGER_WRITER"))
                .andExpect(status().isBadRequest());
        postEntry(postBody(reference, "10.00", "10.00").replace("\"entries\"", "\"extra\":1,\"entries\""),
                as("LEDGER_WRITER")).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("MALFORMED_REQUEST"));
        postEntry(postBody(reference, "10.00", "10.00").replace(source.toString(), "not-a-uuid"),
                as("LEDGER_WRITER")).andExpect(status().isBadRequest());

        assertThat(countEntries()).isZero();
    }

    // ------------------------------------------------------------ lookup

    @Test
    void lookupByLowerCaseReferenceFindsThePostedEntryAndUnknownReferencesAre404() throws Exception {
        String id = json(postEntry(postBody(reference, "10.00", "10.00"), as("LEDGER_WRITER"))
                .andExpect(status().isCreated())).get("id").asText();

        mvc.perform(get(POST_PATH + "/by-reference/" + reference).with(as("LEDGER_READER")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(id))
                .andExpect(jsonPath("$.status").value("POSTED"))
                .andExpect(jsonPath("$.businessReference").value(reference.toUpperCase()));
        mvc.perform(get(POST_PATH + "/by-reference/transfer-" + UUID.randomUUID()).with(as("LEDGER_READER")))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("ENTRY_NOT_FOUND"));
    }

    @Test
    void anAmbiguousRetryCanBeResolvedByLookupWithoutDuplicatePosting() throws Exception {
        // First attempt "times out" on the client side but was committed: the saga looks the entry up...
        String id = json(postEntry(postBody(reference, "10.00", "10.00"), as("LEDGER_WRITER"))
                .andExpect(status().isCreated())).get("id").asText();
        mvc.perform(get(POST_PATH + "/by-reference/" + reference).with(as("LEDGER_WRITER")))
                .andExpect(status().isOk()).andExpect(jsonPath("$.id").value(id));
        // ...and even a blind resend is only a replay.
        postEntry(postBody(reference, "10.00", "10.00"), as("LEDGER_WRITER"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.id").value(id));

        assertThat(countEntries()).isEqualTo(1);
    }

    // ------------------------------------------------------------ reversal

    @Test
    void reversalResolvesTheOriginalReferenceAndAppendsABalancedCompensatingEntry() throws Exception {
        JsonNode original = json(postEntry(postBody(reference, "40.00", "40.00"), as("LEDGER_WRITER"))
                .andExpect(status().isCreated()));
        String reversalRef = reference + "-reversal";

        JsonNode reversal = json(reverse(reference, reversalRef, "TRANSFER_COMPENSATION", as("LEDGER_REVERSER"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.entryType").value("REVERSAL"))
                .andExpect(jsonPath("$.status").value("POSTED"))
                .andExpect(jsonPath("$.businessReference").value(reversalRef.toUpperCase()))
                .andExpect(jsonPath("$.reversesEntryId").value(original.get("id").asText())));

        // Debit and credit swapped on the same accounts; original is untouched.
        assertThat(reversal.get("lines").get(0).get("creditAmount").decimalValue()).isEqualByComparingTo("40.00");
        assertThat(reversal.get("lines").get(1).get("debitAmount").decimalValue()).isEqualByComparingTo("40.00");
        assertThat(reversal.get("lines").get(0).get("accountReference").asText())
                .isEqualTo(source.toString().toUpperCase());
        assertThat(countEntries()).isEqualTo(2);

        // The saga can find the reversal by its own reference afterwards.
        mvc.perform(get(POST_PATH + "/by-reference/" + reversalRef).with(as("LEDGER_READER")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(reversal.get("id").asText()))
                .andExpect(jsonPath("$.status").value("POSTED"));
        mvc.perform(get(POST_PATH + "/by-reference/" + reference).with(as("LEDGER_READER")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(original.get("id").asText()))
                .andExpect(jsonPath("$.reversedByEntryId").value(reversal.get("id").asText()));
    }

    @Test
    void repeatingTheSameReversalReplaysAndAConflictingReversalIsRejected() throws Exception {
        postEntry(postBody(reference, "40.00", "40.00"), as("LEDGER_WRITER")).andExpect(status().isCreated());
        String reversalRef = reference + "-reversal";
        String id = json(reverse(reference, reversalRef, "TRANSFER_COMPENSATION", as("LEDGER_REVERSER"))
                .andExpect(status().isCreated())).get("id").asText();

        reverse(reference, reversalRef, "TRANSFER_COMPENSATION", as("LEDGER_REVERSER"))
                .andExpect(status().isOk())
                .andExpect(header().string("Idempotent-Replay", "true"))
                .andExpect(jsonPath("$.id").value(id));
        // different operation: another reversal reference for the same original
        reverse(reference, reference + "-reversal-2", "TRANSFER_COMPENSATION", as("LEDGER_REVERSER"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("ENTRY_ALREADY_REVERSED"));
        // same reference, different reason
        reverse(reference, reversalRef, "SOMETHING_ELSE", as("LEDGER_REVERSER"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("ENTRY_ALREADY_REVERSED"));

        assertThat(countEntries()).isEqualTo(2);
    }

    @Test
    void reversalOfAnUnknownReferenceIsNotFoundAndDoesNotPostAnything() throws Exception {
        reverse("transfer-" + UUID.randomUUID(), "transfer-" + UUID.randomUUID() + "-reversal", "X_REASON",
                as("LEDGER_REVERSER"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("ENTRY_NOT_FOUND"));
        assertThat(countEntries()).isZero();
    }

    @Test
    void aReversalReferenceAlreadyUsedByAnotherEntryIsAConflict() throws Exception {
        postEntry(postBody(reference, "40.00", "40.00"), as("LEDGER_WRITER")).andExpect(status().isCreated());
        String other = "TRANSFER-" + UUID.randomUUID();
        postEntry(postBody(other, "5.00", "5.00"), as("LEDGER_WRITER")).andExpect(status().isCreated());

        reverse(reference, other, "TRANSFER_COMPENSATION", as("LEDGER_REVERSER"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("BUSINESS_REFERENCE_CONFLICT"));
        assertThat(countEntries()).isEqualTo(2);
    }

    @Test
    void aReversalEntryCannotItselfBeReversed() throws Exception {
        postEntry(postBody(reference, "40.00", "40.00"), as("LEDGER_WRITER")).andExpect(status().isCreated());
        String reversalRef = reference + "-reversal";
        reverse(reference, reversalRef, "TRANSFER_COMPENSATION", as("LEDGER_REVERSER"))
                .andExpect(status().isCreated());

        reverse(reversalRef, reference + "-reversal-of-reversal", "TRANSFER_COMPENSATION", as("LEDGER_REVERSER"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("REVERSAL_NOT_ALLOWED"));
    }

    // ------------------------------------------------------------ security

    @Test
    void requestsWithoutAValidTokenAreUnauthorized() throws Exception {
        mvc.perform(post(POST_PATH).contentType(MediaType.APPLICATION_JSON)
                .content(postBody(reference, "1.00", "1.00"))).andExpect(status().isUnauthorized());
        mvc.perform(post(POST_PATH + "/" + reference + "/reversals").contentType(MediaType.APPLICATION_JSON)
                .content("{}")).andExpect(status().isUnauthorized());
        mvc.perform(get(POST_PATH + "/by-reference/" + reference)).andExpect(status().isUnauthorized());
        // a raw bearer token is rejected by the (test) decoder, exactly like an invalid/expired token
        mvc.perform(get(POST_PATH + "/by-reference/" + reference).header("Authorization", "Bearer not-a-jwt"))
                .andExpect(status().isUnauthorized());
        assertThat(countEntries()).isZero();
    }

    @Test
    void identitiesWithoutTheRequiredRoleAreForbidden() throws Exception {
        String body = postBody(reference, "1.00", "1.00");
        // no roles at all
        postEntry(body, as()).andExpect(status().isForbidden());
        mvc.perform(get(POST_PATH + "/by-reference/" + reference).with(as())).andExpect(status().isForbidden());
        // unrelated roles
        postEntry(body, as("CUSTOMER", "ADMIN")).andExpect(status().isForbidden());
        // reader cannot post or reverse
        postEntry(body, as("LEDGER_READER")).andExpect(status().isForbidden());
        reverse(reference, reference + "-reversal", "TRANSFER_COMPENSATION", as("LEDGER_READER"))
                .andExpect(status().isForbidden());
        // writer cannot reverse; reverser cannot post
        reverse(reference, reference + "-reversal", "TRANSFER_COMPENSATION", as("LEDGER_WRITER"))
                .andExpect(status().isForbidden());
        postEntry(body, as("LEDGER_REVERSER")).andExpect(status().isForbidden());

        assertThat(countEntries()).isZero();
    }

    @Test
    void eachLedgerRoleCanDoExactlyItsOwnInternalOperation() throws Exception {
        postEntry(postBody(reference, "1.00", "1.00"), as("LEDGER_WRITER")).andExpect(status().isCreated());
        reverse(reference, reference + "-reversal", "TRANSFER_COMPENSATION", as("LEDGER_REVERSER"))
                .andExpect(status().isCreated());
        for (String role : new String[]{"LEDGER_READER", "LEDGER_WRITER", "LEDGER_REVERSER"}) {
            mvc.perform(get(POST_PATH + "/by-reference/" + reference).with(as(role)))
                    .andExpect(status().isOk());
        }
    }

    @Test
    void everyOtherInternalRouteRemainsDenied() throws Exception {
        JwtRequestPostProcessor all = as("LEDGER_READER", "LEDGER_WRITER", "LEDGER_REVERSER");
        mvc.perform(get(POST_PATH).with(all)).andExpect(status().isForbidden());
        mvc.perform(get("/internal/other").with(all)).andExpect(status().isForbidden());
        mvc.perform(get("/internal").with(all)).andExpect(status().isForbidden());
        mvc.perform(delete(POST_PATH + "/by-reference/" + reference).with(all)).andExpect(status().isForbidden());
        mvc.perform(post(POST_PATH + "/by-reference/" + reference).with(all)
                .contentType(MediaType.APPLICATION_JSON).content("{}")).andExpect(status().isForbidden());
        mvc.perform(get(POST_PATH + "/" + reference + "/reversals").with(all)).andExpect(status().isForbidden());
    }

    @Test
    void thePublicApiKeepsItsOwnContractAndEntriesPostedInternallyAreVisibleThroughIt() throws Exception {
        String id = json(postEntry(postBody(reference, "10.00", "10.00"), as("LEDGER_WRITER"))
                .andExpect(status().isCreated())).get("id").asText();

        mvc.perform(get("/api/v1/journal-entries/" + id).with(as("LEDGER_READER")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.businessReference").value(reference.toUpperCase()));
        // the strict public contract still refuses lower-case references
        mvc.perform(post("/api/v1/journal-entries").with(as("LEDGER_WRITER"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"businessReference":"%s","currency":"INR","description":"x","lines":[
                                 {"lineNumber":1,"accountReference":"ACC-A","debitAmount":1,"creditAmount":0},
                                 {"lineNumber":2,"accountReference":"ACC-B","debitAmount":0,"creditAmount":1}]}
                                """.formatted(reference)))
                .andExpect(status().isBadRequest());
    }
}
