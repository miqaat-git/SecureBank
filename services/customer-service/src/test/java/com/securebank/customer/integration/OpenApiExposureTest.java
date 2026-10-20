package com.securebank.customer.integration;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.web.servlet.MockMvc;

/** API documentation is closed by default and public only when explicitly enabled. */
class OpenApiExposureTest {

    @Nested
    @SpringBootTest
    @AutoConfigureMockMvc
    class DefaultConfiguration {

        @Autowired
        private MockMvc mockMvc;

        @Test
        void apiDocsAreNotPublicByDefault() throws Exception {
            mockMvc.perform(get("/v3/api-docs")).andExpect(status().isUnauthorized());
        }
    }

    @Nested
    @SpringBootTest(properties = {
            "springdoc.api-docs.enabled=true",
            "securebank.security.public-api-docs=true"
    })
    @AutoConfigureMockMvc
    class DocsEnabled {

        @Autowired
        private MockMvc mockMvc;

        @Test
        void apiDocsArePublicWhenEnabledAndDescribeCustomerEndpoints() throws Exception {
            mockMvc.perform(get("/v3/api-docs"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.info.title").value("SecureBank Customer Service API"))
                    .andExpect(jsonPath("$.paths['/api/v1/customers']").exists());
        }

        @Test
        void customerEndpointsStayProtectedEvenWhenDocsArePublic() throws Exception {
            mockMvc.perform(get("/api/v1/customers")).andExpect(status().isUnauthorized());
        }
    }
}
