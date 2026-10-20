package com.securebank.customer.service;

import com.securebank.customer.dto.CustomerResponse;
import com.securebank.customer.dto.CustomerStatusResponse;
import com.securebank.customer.entity.Customer;

/** Explicit entity-to-DTO mapping. */
public final class CustomerMapper {

    private CustomerMapper() {
    }

    public static CustomerResponse toResponse(Customer customer) {
        return new CustomerResponse(
                customer.getId(),
                customer.getCustomerNumber(),
                customer.getFirstName(),
                customer.getLastName(),
                customer.getEmail(),
                customer.getPhone(),
                customer.getStatus(),
                customer.getCreatedAt(),
                customer.getUpdatedAt());
    }

    public static CustomerStatusResponse toStatusResponse(Customer customer) {
        return new CustomerStatusResponse(customer.getId(), customer.getStatus(), customer.getUpdatedAt());
    }
}
