package com.securebank.customer.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.securebank.customer.entity.Customer;
import com.securebank.customer.repository.CustomerRepository;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

/**
 * End-to-end tests: real controller, service, repository, security rules and an isolated H2 database.
 * No test depends on another or on pre-existing rows: every test uses unique emails/names.
 */
@SpringBootTest
@AutoConfigureMockMvc
class CustomerApiIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private CustomerRepository customerRepository;

    private static RequestPostProcessor staff() {
        return jwt().authorities(new SimpleGrantedAuthority("ROLE_STAFF"));
    }

    private static RequestPostProcessor admin() {
        return jwt().authorities(new SimpleGrantedAuthority("ROLE_ADMIN"));
    }

    private static String uniqueToken() {
        return UUID.randomUUID().toString().replace("-", "").substring(0, 12);
    }

    private String json(String firstName, String lastName, String email, String phone) throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("firstName", firstName);
        body.put("lastName", lastName);
        body.put("email", email);
        if (phone != null) {
            body.put("phone", phone);
        }
        return objectMapper.writeValueAsString(body);
    }

    /** Creates a customer through the API and returns its id. */
    private UUID createCustomer(String firstName, String lastName, String email) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/v1/customers").with(staff())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(firstName, lastName, email, null)))
                .andExpect(status().isCreated())
                .andReturn();
        JsonNode node = objectMapper.readTree(result.getResponse().getContentAsString());
        return UUID.fromString(node.get("id").asText());
    }

    @Test
    void createThenRetrieveCustomer() throws Exception {
        String token = uniqueToken();
        String email = "Asha." + token + "@Example.TEST";

        MvcResult created = mockMvc.perform(post("/api/v1/customers").with(staff())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json("Asha", "Verma" + "x", email, "+919000000001")))
                .andExpect(status().isCreated())
                .andExpect(header().string("Location", containsString("/api/v1/customers/")))
                .andExpect(jsonPath("$.email").value(email.toLowerCase()))
                .andExpect(jsonPath("$.status").value("ACTIVE"))
                .andExpect(jsonPath("$.customerNumber").value(org.hamcrest.Matchers.matchesPattern("CUS\\d{12}")))
                .andReturn();
        UUID id = UUID.fromString(objectMapper.readTree(created.getResponse().getContentAsString()).get("id").asText());

        mockMvc.perform(get("/api/v1/customers/{id}", id).with(staff()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(id.toString()))
                .andExpect(jsonPath("$.firstName").value("Asha"))
                .andExpect(jsonPath("$.phone").value("+919000000001"))
                .andExpect(jsonPath("$.createdAt").exists())
                .andExpect(jsonPath("$.updatedAt").exists());
    }

    @Test
    void retrievingUnknownCustomerReturns404() throws Exception {
        mockMvc.perform(get("/api/v1/customers/{id}", UUID.randomUUID()).with(staff()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.status").value(404));
    }

    @Test
    void invalidCreateRequestReturns400WithFieldErrors() throws Exception {
        mockMvc.perform(post("/api/v1/customers").with(staff())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json("", "Verma", "not-an-email", "abc")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.fieldErrors").isArray())
                .andExpect(jsonPath("$.fieldErrors[?(@.field=='firstName')]").exists())
                .andExpect(jsonPath("$.fieldErrors[?(@.field=='email')]").exists())
                .andExpect(jsonPath("$.fieldErrors[?(@.field=='phone')]").exists());
    }

    @Test
    void duplicateEmailIsRejectedCaseInsensitively() throws Exception {
        String token = uniqueToken();
        createCustomer("Dup", "Original", "dup." + token + "@example.test");

        mockMvc.perform(post("/api/v1/customers").with(staff())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json("Dup", "Copy", "DUP." + token + "@EXAMPLE.test", null)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.message").value("A customer with this email address already exists."));
    }

    @Test
    void updateChangesProfileAndRejectsEmailOfAnotherCustomer() throws Exception {
        String token = uniqueToken();
        UUID first = createCustomer("Upd", "One", "upd1." + token + "@example.test");
        createCustomer("Upd", "Two", "upd2." + token + "@example.test");

        mockMvc.perform(put("/api/v1/customers/{id}", first).with(staff())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json("Updated", "Name", "upd1.new." + token + "@example.test", "+911234567890")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.firstName").value("Updated"))
                .andExpect(jsonPath("$.email").value("upd1.new." + token + "@example.test"))
                .andExpect(jsonPath("$.phone").value("+911234567890"));

        mockMvc.perform(put("/api/v1/customers/{id}", first).with(staff())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json("Updated", "Name", "upd2." + token + "@example.test", null)))
                .andExpect(status().isConflict());

        // Keeping your own email is not a conflict.
        mockMvc.perform(put("/api/v1/customers/{id}", first).with(staff())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json("Again", "Name", "upd1.new." + token + "@example.test", null)))
                .andExpect(status().isOk());
    }

    @Test
    void searchPaginatesAndMatchesNameOrEmail() throws Exception {
        String token = uniqueToken();
        createCustomer("Searchy", "Zed" + letters(token) + "a", "s1." + token + "@example.test");
        createCustomer("Searchy", "Zed" + letters(token) + "b", "s2." + token + "@example.test");

        // match on email text shared by both customers, one per page
        mockMvc.perform(get("/api/v1/customers/search").with(staff())
                        .param("q", token.toUpperCase()).param("size", "1").param("page", "0"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(2))
                .andExpect(jsonPath("$.totalPages").value(2))
                .andExpect(jsonPath("$.content.length()").value(1))
                .andExpect(jsonPath("$.first").value(true));

        mockMvc.perform(get("/api/v1/customers/search").with(staff())
                        .param("q", token).param("size", "1").param("page", "1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.last").value(true));

        // match on a name fragment
        mockMvc.perform(get("/api/v1/customers/search").with(staff()).param("q", "zed" + letters(token) + "a"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(1));
    }

    @Test
    void searchTreatsWildcardCharactersLiterally() throws Exception {
        createCustomer("Wild", "Card" + letters(uniqueToken()), "wild." + uniqueToken() + "@example.test");

        mockMvc.perform(get("/api/v1/customers/search").with(staff()).param("q", "%"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(0));
        mockMvc.perform(get("/api/v1/customers/search").with(staff()).param("q", "_"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(0));
    }

    @Test
    void searchRequiresNonBlankText() throws Exception {
        mockMvc.perform(get("/api/v1/customers/search").with(staff()).param("q", "   "))
                .andExpect(status().isBadRequest());
        mockMvc.perform(get("/api/v1/customers/search").with(staff()))
                .andExpect(status().isBadRequest());
    }

    @Test
    void listSupportsPagingAndSortingAndRejectsBadParameters() throws Exception {
        createCustomer("List", "Alpha" + letters(uniqueToken()), "list1." + uniqueToken() + "@example.test");
        createCustomer("List", "Beta" + letters(uniqueToken()), "list2." + uniqueToken() + "@example.test");

        mockMvc.perform(get("/api/v1/customers").with(staff())
                        .param("page", "0").param("size", "2").param("sortBy", "lastName").param("direction", "asc"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content.length()").value(2))
                .andExpect(jsonPath("$.size").value(2))
                .andExpect(jsonPath("$.totalElements").isNumber());

        mockMvc.perform(get("/api/v1/customers").with(staff()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.size").value(20));

        mockMvc.perform(get("/api/v1/customers").with(staff()).param("sortBy", "passwordHash"))
                .andExpect(status().isBadRequest());
        mockMvc.perform(get("/api/v1/customers").with(staff()).param("size", "101"))
                .andExpect(status().isBadRequest());
        mockMvc.perform(get("/api/v1/customers").with(staff()).param("page", "-1"))
                .andExpect(status().isBadRequest());
        mockMvc.perform(get("/api/v1/customers").with(staff()).param("page", "abc"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void adminSuspendsAndActivatesAndStatusIsReadable() throws Exception {
        UUID id = createCustomer("Stat", "Change", "stat." + uniqueToken() + "@example.test");

        mockMvc.perform(get("/api/v1/customers/{id}/status", id).with(staff()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.customerId").value(id.toString()))
                .andExpect(jsonPath("$.status").value("ACTIVE"));

        mockMvc.perform(patch("/api/v1/customers/{id}/status", id).with(admin())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"status\":\"SUSPENDED\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("SUSPENDED"));

        mockMvc.perform(get("/api/v1/customers/{id}/status", id).with(staff()))
                .andExpect(jsonPath("$.status").value("SUSPENDED"));

        mockMvc.perform(patch("/api/v1/customers/{id}/status", id).with(admin())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"status\":\"ACTIVE\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("ACTIVE"));
    }

    @Test
    void statusChangeNeedsAdminRole() throws Exception {
        UUID id = createCustomer("Stat", "Denied", "denied." + uniqueToken() + "@example.test");

        mockMvc.perform(patch("/api/v1/customers/{id}/status", id).with(staff())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"status\":\"SUSPENDED\"}"))
                .andExpect(status().isForbidden());
        mockMvc.perform(patch("/api/v1/customers/{id}/status", id)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"status\":\"SUSPENDED\"}"))
                .andExpect(status().isUnauthorized());

        // and nothing changed
        mockMvc.perform(get("/api/v1/customers/{id}/status", id).with(staff()))
                .andExpect(jsonPath("$.status").value("ACTIVE"));
    }

    @Test
    void statusCannotBeSetToClosedOrToUnknownValue() throws Exception {
        UUID id = createCustomer("Stat", "Closed", "closed." + uniqueToken() + "@example.test");

        mockMvc.perform(patch("/api/v1/customers/{id}/status", id).with(admin())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"status\":\"CLOSED\"}"))
                .andExpect(status().isBadRequest());
        mockMvc.perform(patch("/api/v1/customers/{id}/status", id).with(admin())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"status\":\"BLOCKED\"}"))
                .andExpect(status().isBadRequest());
        mockMvc.perform(patch("/api/v1/customers/{id}/status", id).with(admin())
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void closedCustomerCannotBeModifiedOrReactivated() throws Exception {
        String token = uniqueToken();
        UUID id = createCustomer("Gone", "Away", "gone." + token + "@example.test");
        Customer customer = customerRepository.findById(id).orElseThrow();
        customer.setStatus(Customer.Status.CLOSED);
        customerRepository.saveAndFlush(customer);

        mockMvc.perform(patch("/api/v1/customers/{id}/status", id).with(admin())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"status\":\"ACTIVE\"}"))
                .andExpect(status().isConflict());
        mockMvc.perform(put("/api/v1/customers/{id}", id).with(staff())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json("Gone", "Away", "gone." + token + "@example.test", null)))
                .andExpect(status().isConflict());
        mockMvc.perform(get("/api/v1/customers/{id}/status", id).with(staff()))
                .andExpect(jsonPath("$.status").value("CLOSED"));
    }

    @Test
    void customerEndpointsRequireAuthenticationAndRole() throws Exception {
        mockMvc.perform(get("/api/v1/customers"))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(get("/api/v1/customers")
                        .with(jwt().authorities(new SimpleGrantedAuthority("ROLE_UNRELATED"))))
                .andExpect(status().isForbidden());
        mockMvc.perform(get("/api/v1/customers").with(admin()))
                .andExpect(status().isOk());
    }

    @Test
    void responsesNeverContainSecretsOrInternalFields() throws Exception {
        UUID id = createCustomer("Safe", "Output", "safe." + uniqueToken() + "@example.test");

        MvcResult result = mockMvc.perform(get("/api/v1/customers/{id}", id).with(staff()))
                .andExpect(status().isOk())
                .andReturn();
        JsonNode node = objectMapper.readTree(result.getResponse().getContentAsString());

        assertThat(node.fieldNames()).toIterable().containsExactlyInAnyOrder(
                "id", "customerNumber", "firstName", "lastName", "email", "phone", "status", "createdAt", "updatedAt");
    }

    /** Names only allow letters, so turn a hex token into letters. */
    private static String letters(String token) {
        StringBuilder out = new StringBuilder();
        for (char c : token.toCharArray()) {
            out.append(Character.isDigit(c) ? (char) ('g' + (c - '0')) : c);
        }
        return out.toString();
    }
}
