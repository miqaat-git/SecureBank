package com.securebank.ledger.web;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.notNullValue;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.securebank.ledger.support.AbstractLedgerTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.JwtRequestPostProcessor;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

/** HTTP contract, validation, error handling and role-based authorization (real security filter chain). */
class JournalEntryApiTest extends AbstractLedgerTest {

    private static final String BALANCED = """
            {
              "businessReference": "TXN-API-0001",
              "currency": "INR",
              "description": "Simulated deposit",
              "lines": [
                {"lineNumber": 1, "accountReference": "ACC-CASH", "debitAmount": 1000.00, "creditAmount": 0},
                {"lineNumber": 2, "accountReference": "ACC-CUST-1", "debitAmount": 0, "creditAmount": 1000.00}
              ]
            }
            """;

    private static final String UNBALANCED = """
            {
              "businessReference": "TXN-API-0002",
              "currency": "INR",
              "description": "Does not balance",
              "lines": [
                {"lineNumber": 1, "accountReference": "ACC-CASH", "debitAmount": 1000.00, "creditAmount": 0},
                {"lineNumber": 2, "accountReference": "ACC-CUST-1", "debitAmount": 0, "creditAmount": 999.99}
              ]
            }
            """;

    @Autowired
    private MockMvc mvc;

    @Autowired
    private ObjectMapper objectMapper;

    private static JwtRequestPostProcessor as(String... roles) {
        JwtRequestPostProcessor processor = jwt().jwt(token -> token.subject("test-user"));
        SimpleGrantedAuthority[] authorities = new SimpleGrantedAuthority[roles.length];
        for (int i = 0; i < roles.length; i++) {
            authorities[i] = new SimpleGrantedAuthority("ROLE_" + roles[i]);
        }
        return processor.authorities(authorities);
    }

    private JsonNode postEntry(String json) throws Exception {
        MvcResult result = mvc.perform(post("/api/v1/journal-entries").with(as("LEDGER_WRITER"))
                        .contentType(MediaType.APPLICATION_JSON).content(json))
                .andExpect(status().isCreated()).andReturn();
        return objectMapper.readTree(result.getResponse().getContentAsString());
    }

    // ------------------------------------------------------------ happy paths

    @Test
    void postsBalancedEntryAndReturns201WithLocation() throws Exception {
        mvc.perform(post("/api/v1/journal-entries").with(as("LEDGER_WRITER"))
                        .contentType(MediaType.APPLICATION_JSON).content(BALANCED))
                .andExpect(status().isCreated())
                .andExpect(header().string("Location", containsString("/api/v1/journal-entries/")))
                .andExpect(jsonPath("$.id", notNullValue()))
                .andExpect(jsonPath("$.status").value("POSTED"))
                .andExpect(jsonPath("$.entryType").value("NORMAL"))
                .andExpect(jsonPath("$.businessReference").value("TXN-API-0001"))
                .andExpect(jsonPath("$.createdBy").value("test-user"))
                .andExpect(jsonPath("$.lines", hasSize(2)));
    }

    @Test
    void identicalRetryReturns200WithReplayHeaderAndSameEntry() throws Exception {
        JsonNode first = postEntry(BALANCED);

        mvc.perform(post("/api/v1/journal-entries").with(as("LEDGER_WRITER"))
                        .contentType(MediaType.APPLICATION_JSON).content(BALANCED))
                .andExpect(status().isOk())
                .andExpect(header().string("Idempotent-Replay", "true"))
                .andExpect(jsonPath("$.id").value(first.get("id").asText()));
    }

    @Test
    void conflictingReuseOfReferenceReturns409() throws Exception {
        postEntry(BALANCED);
        String changed = BALANCED.replace("1000.00", "2000.00");

        mvc.perform(post("/api/v1/journal-entries").with(as("LEDGER_WRITER"))
                        .contentType(MediaType.APPLICATION_JSON).content(changed))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("BUSINESS_REFERENCE_CONFLICT"))
                .andExpect(jsonPath("$.status").value(409))
                .andExpect(jsonPath("$.path").value("/api/v1/journal-entries"));
    }

    @Test
    void readsEntryByIdByReferenceAndItsLines() throws Exception {
        String id = postEntry(BALANCED).get("id").asText();

        mvc.perform(get("/api/v1/journal-entries/{id}", id).with(as("LEDGER_READER")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(id))
                .andExpect(jsonPath("$.lines", hasSize(2)));
        mvc.perform(get("/api/v1/journal-entries/by-reference/{ref}", "TXN-API-0001").with(as("LEDGER_READER")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(id));
        mvc.perform(get("/api/v1/journal-entries/{id}/lines", id).with(as("LEDGER_READER")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(2)))
                .andExpect(jsonPath("$[0].lineNumber").value(1))
                .andExpect(jsonPath("$[0].accountReference").value("ACC-CASH"));
    }

    @Test
    void listsEntriesWithPaginationAndFilters() throws Exception {
        postEntry(BALANCED);

        mvc.perform(get("/api/v1/journal-entries").param("size", "5").param("status", "POSTED")
                        .param("reference", "TXN-API-0001").with(as("LEDGER_READER")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(1))
                .andExpect(jsonPath("$.content", hasSize(1)))
                .andExpect(jsonPath("$.content[0].businessReference").value("TXN-API-0001"));
        mvc.perform(get("/api/v1/journal-entries").param("from", "2999-01-01T00:00:00Z").with(as("LEDGER_READER")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(0));
    }

    @Test
    void exposesAccountLedgerLinesAndMovement() throws Exception {
        postEntry(BALANCED);

        mvc.perform(get("/api/v1/accounts/{a}/ledger-lines", "ACC-CASH").with(as("LEDGER_READER")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(1))
                .andExpect(jsonPath("$.content[0].businessReference").value("TXN-API-0001"));
        mvc.perform(get("/api/v1/accounts/{a}/movement", "ACC-CASH").with(as("LEDGER_READER")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.accountReference").value("ACC-CASH"))
                .andExpect(jsonPath("$.currency").value("INR"))
                .andExpect(jsonPath("$.lineCount").value(1))
                .andExpect(jsonPath("$.netMovement").value(1000.0))
                .andExpect(jsonPath("$.convention", containsString("totalDebits - totalCredits")));
    }

    @Test
    void reversalCreatesCompensatingEntryAndIsIdempotent() throws Exception {
        String id = postEntry(BALANCED).get("id").asText();
        String body = "{\"businessReference\":\"REV-API-0001\",\"reason\":\"Posted in error\"}";

        mvc.perform(post("/api/v1/journal-entries/{id}/reversals", id).with(as("LEDGER_REVERSER"))
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.entryType").value("REVERSAL"))
                .andExpect(jsonPath("$.reversesEntryId").value(id));
        mvc.perform(post("/api/v1/journal-entries/{id}/reversals", id).with(as("LEDGER_REVERSER"))
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk())
                .andExpect(header().string("Idempotent-Replay", "true"));
        mvc.perform(post("/api/v1/journal-entries/{id}/reversals", id).with(as("LEDGER_REVERSER"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"businessReference\":\"REV-API-0002\",\"reason\":\"again\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("ENTRY_ALREADY_REVERSED"));
        mvc.perform(get("/api/v1/journal-entries/{id}", id).with(as("LEDGER_READER")))
                .andExpect(jsonPath("$.reversedByEntryId", notNullValue()));
    }

    // ------------------------------------------------------------ validation & errors

    @Test
    void unbalancedEntryReturns422() throws Exception {
        mvc.perform(post("/api/v1/journal-entries").with(as("LEDGER_WRITER"))
                        .contentType(MediaType.APPLICATION_JSON).content(UNBALANCED))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("UNBALANCED_ENTRY"));
        assertNothingPersisted();
    }

    @Test
    void tooPreciseAmountAndSingleLineReturn422() throws Exception {
        String tooPrecise = BALANCED.replace("TXN-API-0001", "TXN-API-0003").replace("1000.00", "1000.005");
        mvc.perform(post("/api/v1/journal-entries").with(as("LEDGER_WRITER"))
                        .contentType(MediaType.APPLICATION_JSON).content(tooPrecise))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("PRECISION_EXCEEDED"));

        String oneLine = """
                {"businessReference":"TXN-API-0004","currency":"INR","description":"one line",
                 "lines":[{"lineNumber":1,"accountReference":"ACC-CASH","debitAmount":5,"creditAmount":0}]}
                """;
        mvc.perform(post("/api/v1/journal-entries").with(as("LEDGER_WRITER"))
                        .contentType(MediaType.APPLICATION_JSON).content(oneLine))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("TOO_FEW_LINES"));
        assertNothingPersisted();
    }

    @Test
    void missingAndMalformedFieldsReturn400WithViolations() throws Exception {
        mvc.perform(post("/api/v1/journal-entries").with(as("LEDGER_WRITER"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"businessReference\":\"bad ref\",\"currency\":\"inr\",\"lines\":null}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.violations").isArray());
        assertNothingPersisted();
    }

    @Test
    void malformedJsonAndUnknownFieldsReturn400() throws Exception {
        mvc.perform(post("/api/v1/journal-entries").with(as("LEDGER_WRITER"))
                        .contentType(MediaType.APPLICATION_JSON).content("{not json"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("MALFORMED_REQUEST"));
        mvc.perform(post("/api/v1/journal-entries").with(as("LEDGER_WRITER"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(BALANCED.replace("\"currency\"", "\"unexpectedField\": 1, \"currency\"")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("MALFORMED_REQUEST"));
    }

    @Test
    void unknownEntryReturns404AndBadIdReturns400() throws Exception {
        mvc.perform(get("/api/v1/journal-entries/{id}", "3f2b8c1e-0000-4000-8000-000000000000")
                        .with(as("LEDGER_READER")))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("ENTRY_NOT_FOUND"));
        mvc.perform(get("/api/v1/journal-entries/{id}", "not-a-uuid").with(as("LEDGER_READER")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_PARAMETER"));
    }

    @Test
    void invalidQueryParametersReturn400() throws Exception {
        mvc.perform(get("/api/v1/journal-entries").param("size", "0").with(as("LEDGER_READER")))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("INVALID_QUERY"));
        mvc.perform(get("/api/v1/journal-entries").param("size", "1000").with(as("LEDGER_READER")))
                .andExpect(status().isBadRequest());
        mvc.perform(get("/api/v1/journal-entries").param("from", "yesterday").with(as("LEDGER_READER")))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("INVALID_PARAMETER"));
        mvc.perform(get("/api/v1/journal-entries").param("from", "2026-02-01T00:00:00Z")
                        .param("to", "2026-01-01T00:00:00Z").with(as("LEDGER_READER")))
                .andExpect(status().isBadRequest());
        mvc.perform(get("/api/v1/accounts/{a}/movement", "lower-case").with(as("LEDGER_READER")))
                .andExpect(status().isBadRequest());
    }

    // ------------------------------------------------------------ authentication & authorization

    @Test
    void requestsWithoutTokenGet401WithJsonError() throws Exception {
        mvc.perform(get("/api/v1/journal-entries"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHENTICATED"));
        mvc.perform(post("/api/v1/journal-entries").contentType(MediaType.APPLICATION_JSON).content(BALANCED))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void invalidBearerTokenGets401() throws Exception {
        mvc.perform(get("/api/v1/journal-entries").header("Authorization", "Bearer not-a-real-token"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void readerCannotPostOrReverse() throws Exception {
        mvc.perform(post("/api/v1/journal-entries").with(as("LEDGER_READER"))
                        .contentType(MediaType.APPLICATION_JSON).content(BALANCED))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("ACCESS_DENIED"));
        mvc.perform(post("/api/v1/journal-entries/{id}/reversals", "3f2b8c1e-0000-4000-8000-000000000000")
                        .with(as("LEDGER_READER")).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"businessReference\":\"REV-X-0001\",\"reason\":\"x\"}"))
                .andExpect(status().isForbidden());
        assertNothingPersisted();
    }

    @Test
    void writerCannotReverseAndReverserCannotPost() throws Exception {
        String id = postEntry(BALANCED).get("id").asText();
        mvc.perform(post("/api/v1/journal-entries/{id}/reversals", id).with(as("LEDGER_WRITER"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"businessReference\":\"REV-API-0009\",\"reason\":\"x\"}"))
                .andExpect(status().isForbidden());
        mvc.perform(post("/api/v1/journal-entries").with(as("LEDGER_REVERSER"))
                        .contentType(MediaType.APPLICATION_JSON).content(BALANCED.replace("TXN-API-0001", "TXN-API-0010")))
                .andExpect(status().isForbidden());
    }

    @Test
    void authenticatedUserWithoutLedgerRoleIsForbiddenEverywhere() throws Exception {
        mvc.perform(get("/api/v1/journal-entries").with(as("SOME_OTHER_ROLE"))).andExpect(status().isForbidden());
        mvc.perform(get("/api/v1/accounts/{a}/movement", "ACC-CASH").with(as())).andExpect(status().isForbidden());
    }

    @Test
    void unknownPathsAreDeniedByDefault() throws Exception {
        mvc.perform(get("/internal/anything").with(as("LEDGER_READER"))).andExpect(status().isForbidden());
    }

    // ------------------------------------------------------------ public endpoints

    @Test
    void healthEndpointIsPublicAndHidesDetails() throws Exception {
        mvc.perform(get("/actuator/health"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("UP"))
                .andExpect(jsonPath("$.components").doesNotExist());
    }

    @Test
    void apiDocsArePublicByDefaultAndListLedgerPaths() throws Exception {
        mvc.perform(get("/v3/api-docs"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.paths['/api/v1/journal-entries']").exists());
    }

    @Test
    void otherActuatorEndpointsAreNotExposed() throws Exception {
        mvc.perform(get("/actuator/env")).andExpect(status().isUnauthorized());
        mvc.perform(get("/actuator/env").with(as("LEDGER_READER"))).andExpect(status().isForbidden());
    }

    private void assertNothingPersisted() {
        org.assertj.core.api.Assertions.assertThat(countEntries()).isZero();
        org.assertj.core.api.Assertions.assertThat(countLines()).isZero();
    }
}
