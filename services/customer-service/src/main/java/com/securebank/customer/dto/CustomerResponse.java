package com.securebank.customer.dto;

import com.securebank.customer.entity.Customer;

import java.time.LocalDateTime;
import java.util.UUID;

/** Customer as returned by the API. The JPA entity is never exposed. */
public record CustomerResponse(
        UUID id,
        String customerNumber,
        String firstName,
        String lastName,
        String email,
        String phone,
        Customer.Status status,
        LocalDateTime createdAt,
        LocalDateTime updatedAt) {
}
