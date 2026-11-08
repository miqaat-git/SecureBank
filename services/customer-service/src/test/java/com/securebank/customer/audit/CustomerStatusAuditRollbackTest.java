package com.securebank.customer.audit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.securebank.customer.entity.Customer;
import com.securebank.customer.entity.CustomerStatusAudit;
import com.securebank.customer.repository.CustomerRepository;
import com.securebank.customer.repository.CustomerStatusAuditRepository;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

/** If the audit insert fails, the status change is rolled back: the two commit together or not at all. */
@SpringBootTest
@AutoConfigureMockMvc
class CustomerStatusAuditRollbackTest {

    @Autowired MockMvc mockMvc;
    @Autowired ObjectMapper objectMapper;
    @Autowired CustomerRepository customerRepository;
    @MockitoBean CustomerStatusAuditRepository auditRepository;

    @Test
    void aFailingAuditInsertRollsTheStatusChangeBack() throws Exception {
        when(auditRepository.save(any(CustomerStatusAudit.class))).thenThrow(new IllegalStateException("audit down"));
        String token = UUID.randomUUID().toString().replace("-", "").substring(0, 12);
        String created = mockMvc.perform(post("/api/v1/customers")
                        .with(jwt().authorities(new SimpleGrantedAuthority("ROLE_STAFF")))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"firstName\":\"Roll\",\"lastName\":\"Back\",\"email\":\"roll." + token
                                + "@example.test\"}"))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        UUID id = UUID.fromString(objectMapper.readTree(created).get("id").asText());

        mockMvc.perform(patch("/api/v1/customers/{id}/status", id)
                        .with(jwt().jwt(j -> j.subject("admin-sub-1"))
                                .authorities(new SimpleGrantedAuthority("ROLE_ADMIN")))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"status\":\"SUSPENDED\",\"reason\":\"will roll back\"}"))
                .andExpect(status().isInternalServerError());

        assertThat(customerRepository.findById(id).orElseThrow().getStatus()).isEqualTo(Customer.Status.ACTIVE);
    }
}
