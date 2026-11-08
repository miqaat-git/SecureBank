package com.securebank.customer.audit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.securebank.customer.entity.Customer;
import com.securebank.customer.entity.CustomerStatusAudit;
import com.securebank.customer.repository.CustomerRepository;
import com.securebank.customer.repository.CustomerStatusAuditRepository;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.MediaType;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

/** Customer status audit: same-transaction, append-only, admin-only, no misleading rows for failed changes. */
@SpringBootTest
@AutoConfigureMockMvc
class CustomerStatusAuditTest {

    @Autowired MockMvc mockMvc;
    @Autowired ObjectMapper objectMapper;
    @Autowired CustomerRepository customerRepository;
    @Autowired CustomerStatusAuditRepository auditRepository;

    private static RequestPostProcessor admin(String subject) {
        return jwt().jwt(j -> j.subject(subject)).authorities(new SimpleGrantedAuthority("ROLE_ADMIN"));
    }

    private static RequestPostProcessor staff() {
        return jwt().jwt(j -> j.subject("staff-sub-9")).authorities(new SimpleGrantedAuthority("ROLE_STAFF"));
    }

    private UUID newCustomer() throws Exception {
        String token = UUID.randomUUID().toString().replace("-", "").substring(0, 12);
        String body = "{\"firstName\":\"Audit\",\"lastName\":\"Subject\",\"email\":\"audit." + token
                + "@example.test\"}";
        String json = mockMvc.perform(post("/api/v1/customers").with(staff())
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        JsonNode node = objectMapper.readTree(json);
        return UUID.fromString(node.get("id").asText());
    }

    private static String change(String status, String reason) {
        return reason == null ? "{\"status\":\"" + status + "\"}"
                : "{\"status\":\"" + status + "\",\"reason\":\"" + reason + "\"}";
    }

    private List<CustomerStatusAudit> history(UUID id) {
        return auditRepository.findByCustomerId(id, PageRequest.of(0, 100)).getContent();
    }

    @Test
    void aSuccessfulChangeWritesOneAuditRowWithActorReasonTransitionAndCorrelationId() throws Exception {
        UUID id = newCustomer();

        mockMvc.perform(patch("/api/v1/customers/{id}/status", id).with(admin("admin-sub-42"))
                        .header("X-Correlation-Id", "corr-audit-0001")
                        .contentType(MediaType.APPLICATION_JSON).content(change("SUSPENDED", "Suspected fraud review")))
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("SUSPENDED"));

        assertThat(history(id)).singleElement().satisfies(a -> {
            assertThat(a.getCustomerId()).isEqualTo(id);
            assertThat(a.getPreviousStatus()).isEqualTo(Customer.Status.ACTIVE);
            assertThat(a.getNewStatus()).isEqualTo(Customer.Status.SUSPENDED);
            assertThat(a.getReason()).isEqualTo("Suspected fraud review");
            assertThat(a.getActorSubject()).isEqualTo("admin-sub-42");   // from the token, never the body
            assertThat(a.getActorRole()).isEqualTo("ADMIN");
            assertThat(a.getCorrelationId()).isEqualTo("corr-audit-0001");
            assertThat(a.getOccurredAt()).isNotNull();
        });

        mockMvc.perform(patch("/api/v1/customers/{id}/status", id).with(admin("admin-sub-43"))
                        .contentType(MediaType.APPLICATION_JSON).content(change("ACTIVE", "Review cleared")))
                .andExpect(status().isOk());
        assertThat(history(id)).extracting(CustomerStatusAudit::getNewStatus)
                .containsExactly(Customer.Status.SUSPENDED, Customer.Status.ACTIVE);
    }

    @Test
    void rejectedOrFailedChangesLeaveNoAuditRow() throws Exception {
        UUID id = newCustomer();

        // missing / blank / overlong / control-character reason
        for (String body : new String[] {change("SUSPENDED", null), change("SUSPENDED", "   "),
                change("SUSPENDED", "x".repeat(256)), change("SUSPENDED", "bad\\u0007reason")}) {
            mockMvc.perform(patch("/api/v1/customers/{id}/status", id).with(admin("admin-sub-1"))
                            .contentType(MediaType.APPLICATION_JSON).content(body))
                    .andExpect(status().isBadRequest());
        }
        // illegal target and no-op
        mockMvc.perform(patch("/api/v1/customers/{id}/status", id).with(admin("admin-sub-1"))
                        .contentType(MediaType.APPLICATION_JSON).content(change("CLOSED", "closing")))
                .andExpect(status().isBadRequest());
        mockMvc.perform(patch("/api/v1/customers/{id}/status", id).with(admin("admin-sub-1"))
                        .contentType(MediaType.APPLICATION_JSON).content(change("ACTIVE", "already active")))
                .andExpect(status().isOk());
        // unknown customer, non-admin callers
        mockMvc.perform(patch("/api/v1/customers/{id}/status", UUID.randomUUID()).with(admin("admin-sub-1"))
                        .contentType(MediaType.APPLICATION_JSON).content(change("SUSPENDED", "unknown")))
                .andExpect(status().isNotFound());
        mockMvc.perform(patch("/api/v1/customers/{id}/status", id).with(staff())
                        .contentType(MediaType.APPLICATION_JSON).content(change("SUSPENDED", "staff attempt")))
                .andExpect(status().isForbidden());
        mockMvc.perform(patch("/api/v1/customers/{id}/status", id)
                        .contentType(MediaType.APPLICATION_JSON).content(change("SUSPENDED", "anonymous")))
                .andExpect(status().isUnauthorized());

        assertThat(history(id)).isEmpty();
        assertThat(customerRepository.findById(id).orElseThrow().getStatus()).isEqualTo(Customer.Status.ACTIVE);
    }

    @Test
    void aClosedCustomerCannotChangeStatusAndIsNotAudited() throws Exception {
        UUID id = newCustomer();
        Customer customer = customerRepository.findById(id).orElseThrow();
        customer.setStatus(Customer.Status.CLOSED);
        customerRepository.saveAndFlush(customer);

        mockMvc.perform(patch("/api/v1/customers/{id}/status", id).with(admin("admin-sub-1"))
                        .contentType(MediaType.APPLICATION_JSON).content(change("ACTIVE", "reopen")))
                .andExpect(status().isConflict());
        assertThat(history(id)).isEmpty();
    }

    @Test
    void auditHistoryIsAdminOnlyPaginatedAndOldestFirst() throws Exception {
        UUID id = newCustomer();
        for (int i = 0; i < 3; i++) {
            String target = i % 2 == 0 ? "SUSPENDED" : "ACTIVE";
            mockMvc.perform(patch("/api/v1/customers/{id}/status", id).with(admin("admin-sub-7"))
                            .contentType(MediaType.APPLICATION_JSON).content(change(target, "change " + i)))
                    .andExpect(status().isOk());
        }

        mockMvc.perform(get("/api/v1/customers/{id}/status-audit", id).param("size", "2").with(admin("admin-sub-7")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(3))
                .andExpect(jsonPath("$.content.length()").value(2))
                .andExpect(jsonPath("$.content[0].reason").value("change 0"))
                .andExpect(jsonPath("$.content[0].actorRole").value("ADMIN"))
                .andExpect(jsonPath("$.content[0].email").doesNotExist());
        mockMvc.perform(get("/api/v1/customers/{id}/status-audit", id).with(staff()))
                .andExpect(status().isForbidden());
        mockMvc.perform(get("/api/v1/customers/{id}/status-audit", id)).andExpect(status().isUnauthorized());
        mockMvc.perform(get("/api/v1/customers/{id}/status-audit", UUID.randomUUID()).with(admin("admin-sub-7")))
                .andExpect(status().isNotFound());
        mockMvc.perform(get("/api/v1/customers/{id}/status-audit", id).param("size", "1000").with(admin("admin-sub-7")))
                .andExpect(status().isBadRequest());
    }

    @Test
    void thereIsNoUpdateOrDeleteOperationOnAuditRecordsAndNoPostPutPatchDeleteRoute() throws Exception {
        for (java.lang.reflect.Method m : CustomerStatusAuditRepository.class.getMethods()) {
            assertThat(m.getName()).doesNotStartWith("delete").doesNotStartWith("remove")
                    .doesNotStartWith("update").doesNotContain("Batch");
        }
        assertThat(CustomerStatusAuditRepository.class.getInterfaces())
                .containsExactly(org.springframework.data.repository.Repository.class);
        for (java.lang.reflect.Field f : CustomerStatusAudit.class.getDeclaredFields()) {
            jakarta.persistence.Column column = f.getAnnotation(jakarta.persistence.Column.class);
            if (column != null) {
                assertThat(column.updatable()).as(f.getName()).isFalse();
            }
        }
        UUID id = newCustomer();
        mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                        .delete("/api/v1/customers/{id}/status-audit", id).with(admin("a")))
                .andExpect(status().is4xxClientError());
        mockMvc.perform(post("/api/v1/customers/{id}/status-audit", id).with(admin("a"))
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().is4xxClientError());
    }
}
