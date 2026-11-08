package com.securebank.customer.controller;

import com.securebank.customer.dto.CustomerEligibilityResponse;
import com.securebank.customer.service.CustomerService;
import io.swagger.v3.oas.annotations.Hidden;
import java.util.UUID;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Service-to-service endpoint for the Account Service (ROLE_ACCOUNT_SERVICE only, see SecurityConfig). It is not
 * routed by the API Gateway. It answers 404 for an unknown customer and returns no personal data.
 */
@RestController
@RequestMapping("/internal/v1/customers")
@Hidden
public class InternalCustomerController {

    private final CustomerService customerService;

    public InternalCustomerController(CustomerService customerService) {
        this.customerService = customerService;
    }

    @GetMapping("/{id}/eligibility")
    public CustomerEligibilityResponse eligibility(@PathVariable UUID id) {
        return customerService.getEligibility(id);
    }
}
