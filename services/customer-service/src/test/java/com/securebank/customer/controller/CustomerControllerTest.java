package com.securebank.customer.controller;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;

import com.securebank.customer.dto.ChangeStatusRequest;
import com.securebank.customer.dto.CreateCustomerRequest;
import com.securebank.customer.dto.CustomerResponse;
import com.securebank.customer.dto.CustomerStatusResponse;
import com.securebank.customer.entity.Customer;
import com.securebank.customer.exception.CustomerNotFoundException;
import com.securebank.customer.exception.DuplicateEmailException;
import com.securebank.customer.exception.GlobalExceptionHandler;
import com.securebank.customer.exception.InvalidRequestException;
import com.securebank.customer.security.ApiAccessDeniedHandler;
import com.securebank.customer.security.ApiAuthenticationEntryPoint;
import com.securebank.customer.security.SecurityConfig;
import com.securebank.customer.service.CustomerService;

import java.time.LocalDateTime;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

/** Web-layer tests: real controller, validation, error handling and security rules; mocked service. */
@WebMvcTest(CustomerController.class)
@Import({ SecurityConfig.class, ApiAuthenticationEntryPoint.class, ApiAccessDeniedHandler.class,
        GlobalExceptionHandler.class })
class CustomerControllerTest {

    private static final String VALID_BODY =
            "{\"firstName\":\"Asha\",\"lastName\":\"Verma\",\"email\":\"asha@example.test\",\"phone\":\"+919000000001\"}";

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private CustomerService customerService;

    private static RequestPostProcessor staff() {
        return jwt().authorities(new SimpleGrantedAuthority("ROLE_STAFF"));
    }

    private static RequestPostProcessor admin() {
        return jwt().authorities(new SimpleGrantedAuthority("ROLE_ADMIN"));
    }

    private static CustomerResponse sampleResponse(UUID id) {
        return new CustomerResponse(id, "CUS000000000001", "Asha", "Verma", "asha@example.test", "+919000000001",
                Customer.Status.ACTIVE, LocalDateTime.now(), LocalDateTime.now());
    }

    // ---- success paths ----------------------------------------------------

    @Test
    void createReturns201WithLocationHeader() throws Exception {
        UUID id = UUID.randomUUID();
        when(customerService.create(any(CreateCustomerRequest.class))).thenReturn(sampleResponse(id));

        mockMvc.perform(post("/api/v1/customers").with(staff())
                        .contentType(MediaType.APPLICATION_JSON).content(VALID_BODY))
                .andExpect(status().isCreated())
                .andExpect(header().string("Location", containsString("/api/v1/customers/" + id)))
                .andExpect(jsonPath("$.id").value(id.toString()))
                .andExpect(jsonPath("$.status").value("ACTIVE"));
    }

    @Test
    void getByIdReturnsCustomer() throws Exception {
        UUID id = UUID.randomUUID();
        when(customerService.getById(id)).thenReturn(sampleResponse(id));

        mockMvc.perform(get("/api/v1/customers/{id}", id).with(staff()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.email").value("asha@example.test"));
    }

    @Test
    void adminCanChangeStatus() throws Exception {
        UUID id = UUID.randomUUID();
        when(customerService.changeStatus(eq(id), any(ChangeStatusRequest.class)))
                .thenReturn(new CustomerStatusResponse(id, Customer.Status.SUSPENDED, LocalDateTime.now()));

        mockMvc.perform(patch("/api/v1/customers/{id}/status", id).with(admin())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"status\":\"SUSPENDED\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("SUSPENDED"));
    }

    // ---- validation and error mapping ------------------------------------

    @Test
    void invalidBodyReturns400WithFieldErrors() throws Exception {
        String body = "{\"firstName\":\"\",\"lastName\":\"Verma\",\"email\":\"not-an-email\"}";

        mockMvc.perform(post("/api/v1/customers").with(staff())
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value(400))
                .andExpect(jsonPath("$.fieldErrors[?(@.field=='firstName')]").exists())
                .andExpect(jsonPath("$.fieldErrors[?(@.field=='email')]").exists());
        verifyNoInteractions(customerService);
    }

    @Test
    void unknownFieldsAreRejected() throws Exception {
        String body = "{\"firstName\":\"Asha\",\"lastName\":\"Verma\",\"email\":\"a@example.test\",\"status\":\"CLOSED\"}";

        mockMvc.perform(post("/api/v1/customers").with(staff())
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isBadRequest());
        verifyNoInteractions(customerService);
    }

    @Test
    void malformedJsonReturns400WithoutInternals() throws Exception {
        mockMvc.perform(post("/api/v1/customers").with(staff())
                        .contentType(MediaType.APPLICATION_JSON).content("{not json"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("Malformed or unreadable request body."));
    }

    @Test
    void invalidUuidReturns400() throws Exception {
        mockMvc.perform(get("/api/v1/customers/{id}", "not-a-uuid").with(staff()))
                .andExpect(status().isBadRequest());
    }

    @Test
    void missingCustomerReturns404() throws Exception {
        UUID id = UUID.randomUUID();
        when(customerService.getById(id)).thenThrow(new CustomerNotFoundException(id));

        mockMvc.perform(get("/api/v1/customers/{id}", id).with(staff()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.status").value(404))
                .andExpect(jsonPath("$.path").value("/api/v1/customers/" + id));
    }

    @Test
    void duplicateEmailReturns409() throws Exception {
        when(customerService.create(any(CreateCustomerRequest.class))).thenThrow(new DuplicateEmailException());

        mockMvc.perform(post("/api/v1/customers").with(staff())
                        .contentType(MediaType.APPLICATION_JSON).content(VALID_BODY))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.message").value("A customer with this email address already exists."));
    }

    @Test
    void businessRuleViolationReturns400() throws Exception {
        UUID id = UUID.randomUUID();
        when(customerService.changeStatus(eq(id), any(ChangeStatusRequest.class)))
                .thenThrow(new InvalidRequestException("Status can only be changed to ACTIVE or SUSPENDED."));

        mockMvc.perform(patch("/api/v1/customers/{id}/status", id).with(admin())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"status\":\"CLOSED\"}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void unexpectedExceptionReturns500WithoutLeakingDetails() throws Exception {
        UUID id = UUID.randomUUID();
        when(customerService.getById(id))
                .thenThrow(new RuntimeException("SELECT * FROM customers WHERE password='hunter2' at com.secret.Internal"));

        mockMvc.perform(get("/api/v1/customers/{id}", id).with(staff()))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.message").value("An unexpected error occurred."))
                .andExpect(content().string(not(containsString("SELECT"))))
                .andExpect(content().string(not(containsString("hunter2"))))
                .andExpect(content().string(not(containsString("com.secret"))));
    }

    // ---- security ---------------------------------------------------------

    @Test
    void unauthenticatedRequestReturns401JsonBody() throws Exception {
        mockMvc.perform(get("/api/v1/customers"))
                .andExpect(status().isUnauthorized())
                .andExpect(header().string("WWW-Authenticate", "Bearer"))
                .andExpect(jsonPath("$.status").value(401));
        verifyNoInteractions(customerService);
    }

    @Test
    void invalidBearerTokenReturns401WhenNoIssuerIsConfigured() throws Exception {
        mockMvc.perform(get("/api/v1/customers").header("Authorization", "Bearer forged.token.value"))
                .andExpect(status().isUnauthorized());
        verifyNoInteractions(customerService);
    }

    @Test
    void authenticatedUserWithoutStaffOrAdminRoleGets403() throws Exception {
        mockMvc.perform(get("/api/v1/customers")
                        .with(jwt().authorities(new SimpleGrantedAuthority("ROLE_SOMETHING_ELSE"))))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.status").value(403));
        verifyNoInteractions(customerService);
    }

    @Test
    void staffCannotChangeStatus() throws Exception {
        UUID id = UUID.randomUUID();

        mockMvc.perform(patch("/api/v1/customers/{id}/status", id).with(staff())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"status\":\"SUSPENDED\"}"))
                .andExpect(status().isForbidden());
        verifyNoInteractions(customerService);
    }

    @Test
    void unauthenticatedStatusChangeReturns401() throws Exception {
        UUID id = UUID.randomUUID();

        mockMvc.perform(patch("/api/v1/customers/{id}/status", id)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"status\":\"SUSPENDED\"}"))
                .andExpect(status().isUnauthorized());
    }
}
