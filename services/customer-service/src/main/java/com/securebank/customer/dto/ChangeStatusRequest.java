package com.securebank.customer.dto;

import com.securebank.customer.entity.Customer;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * Request body for the administrative status change. Only ACTIVE and SUSPENDED are accepted by the service, and a
 * reason is mandatory (it is stored in the audit record).
 */
public record ChangeStatusRequest(
        @NotNull(message = "Status is required")
        Customer.Status status,
        @NotBlank(message = "A reason is required")
        @Size(max = 255, message = "The reason must be at most 255 characters")
        String reason) {
}
