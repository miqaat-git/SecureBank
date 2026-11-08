package com.securebank.customer.dto;

import com.securebank.customer.entity.Customer;

import java.time.LocalDateTime;
import java.util.UUID;

public record CustomerStatusResponse(
        UUID customerId,
        Customer.Status status,
        LocalDateTime updatedAt) {
}
