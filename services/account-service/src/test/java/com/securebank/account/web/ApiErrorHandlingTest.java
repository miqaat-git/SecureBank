package com.securebank.account.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.securebank.account.support.AbstractIntegrationTest;
import com.securebank.account.support.TestJwts;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MvcResult;

/** Centralised exception handling: consistent format, correct statuses, no internal details leaked. */
class ApiErrorHandlingTest extends AbstractIntegrationTest {

    @Test
    void errorBodiesUseTheStandardFormat() throws Exception {
        UUID id = UUID.randomUUID();
        mockMvc.perform(get("/api/v1/accounts/" + id).with(TestJwts.admin()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.timestamp").exists())
                .andExpect(jsonPath("$.status").value(404))
                .andExpect(jsonPath("$.error").value("Not Found"))
                .andExpect(jsonPath("$.code").value("ACCOUNT_NOT_FOUND"))
                .andExpect(jsonPath("$.message").value("Account not found."))
                .andExpect(jsonPath("$.path").value("/api/v1/accounts/" + id));
    }

    @Test
    void malformedPathVariableIsBadRequest() throws Exception {
        mockMvc.perform(get("/api/v1/accounts/xyz").with(TestJwts.admin()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("BAD_REQUEST"));
    }

    @Test
    void wrongMethodAndWrongContentTypeAreReportedCorrectly() throws Exception {
        mockMvc.perform(post("/api/v1/accounts/" + UUID.randomUUID() + "/status").with(TestJwts.admin()))
                .andExpect(status().isMethodNotAllowed())
                .andExpect(jsonPath("$.code").value("METHOD_NOT_ALLOWED"));
        mockMvc.perform(post("/api/v1/accounts").with(TestJwts.admin())
                        .contentType(MediaType.TEXT_PLAIN).content("hello"))
                .andExpect(status().isUnsupportedMediaType())
                .andExpect(jsonPath("$.code").value("UNSUPPORTED_MEDIA_TYPE"));
    }

    @Test
    void unexpectedFailuresReturnAGenericMessageWithoutInternalDetails() throws Exception {
        when(customerClient.findCustomer(any())).thenThrow(
                new IllegalStateException("jdbc:mysql://secret-host:3306/db password=hunter2"));

        MvcResult result = mockMvc.perform(post("/api/v1/accounts").with(TestJwts.admin())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"customerId\":\"" + UUID.randomUUID() + "\",\"accountType\":\"SAVINGS\"}"))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.code").value("INTERNAL_ERROR"))
                .andReturn();

        String body = result.getResponse().getContentAsString();
        assertThat(body).doesNotContain("secret-host").doesNotContain("hunter2")
                .doesNotContain("IllegalStateException").doesNotContain("com.securebank");
    }
}
