package com.securebank.account.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.securebank.account.service.AccountService;
import com.securebank.account.support.AbstractIntegrationTest;
import com.securebank.account.support.TestJwts;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationContext;

/** Production security rules (same filter chain as production) for anonymous, customer, admin and service callers. */
class SecurityTest extends AbstractIntegrationTest {

    @Autowired
    private ApplicationContext context;

    @Test
    void applicationContextStarts() {
        assertThat(context.getBean(AccountService.class)).isNotNull();
    }

    @Test
    void healthEndpointIsPublic() throws Exception {
        mockMvc.perform(get("/actuator/health"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("UP"));
    }

    @Test
    void openApiDocumentationIsPublic() throws Exception {
        mockMvc.perform(get("/v3/api-docs"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("SecureBank Account Service API")));
        int swaggerStatus = mockMvc.perform(get("/swagger-ui.html")).andReturn().getResponse().getStatus();
        assertThat(swaggerStatus).isNotIn(401, 403);
    }

    @Test
    void otherActuatorEndpointsAreNotPublic() throws Exception {
        mockMvc.perform(get("/actuator/env")).andExpect(status().isUnauthorized());
        mockMvc.perform(get("/actuator/beans")).andExpect(status().isUnauthorized());
    }

    @Test
    void anonymousRequestsToTheApiGet401WithJsonBody() throws Exception {
        mockMvc.perform(get("/api/v1/accounts/" + UUID.randomUUID()))
                .andExpect(status().isUnauthorized())
                .andExpect(header().string("WWW-Authenticate", "Bearer"))
                .andExpect(jsonPath("$.code").value("AUTHENTICATION_REQUIRED"))
                .andExpect(jsonPath("$.status").value(401));
        mockMvc.perform(get("/api/v1/admin/accounts")).andExpect(status().isUnauthorized());
        mockMvc.perform(get("/internal/v1/accounts/" + UUID.randomUUID() + "/balance-operations"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void invalidBearerTokenIsRejected() throws Exception {
        mockMvc.perform(get("/api/v1/accounts/" + UUID.randomUUID())
                        .header("Authorization", "Bearer not-a-real-token"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("AUTHENTICATION_REQUIRED"));
    }

    @Test
    void transactionServiceTokenCannotUseCustomerOrAdminApis() throws Exception {
        mockMvc.perform(get("/api/v1/accounts/" + UUID.randomUUID()).with(TestJwts.transactionService()))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("ACCESS_DENIED"));
        mockMvc.perform(get("/api/v1/admin/accounts").with(TestJwts.transactionService()))
                .andExpect(status().isForbidden());
    }

    @Test
    void customerTokenCannotReachAdminOrInternalApis() throws Exception {
        UUID customer = UUID.randomUUID();
        mockMvc.perform(get("/api/v1/admin/accounts").with(TestJwts.customer(customer)))
                .andExpect(status().isForbidden());
        mockMvc.perform(get("/internal/v1/accounts/" + UUID.randomUUID() + "/balance-operations")
                        .with(TestJwts.customer(customer)))
                .andExpect(status().isForbidden());
    }

    @Test
    void unknownPathsAreDeniedEvenWhenAuthenticated() throws Exception {
        mockMvc.perform(get("/not-a-route").with(TestJwts.admin())).andExpect(status().isForbidden());
    }
}
