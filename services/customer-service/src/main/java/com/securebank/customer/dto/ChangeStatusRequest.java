package com.securebank.customer.dto;

import com.securebank.customer.entity.Customer;

import jakarta.validation.constraints.NotNull;

/** Request body for the administrative status change. Only ACTIVE and SUSPENDED are accepted by the service. */
public record ChangeStatusRequest(
        @NotNull(message = "Status is required")
        Customer.Status status) {
}
