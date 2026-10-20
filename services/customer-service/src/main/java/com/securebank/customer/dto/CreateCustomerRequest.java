package com.securebank.customer.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/** Request body for creating a customer. Status and ids are never accepted from clients. */
public record CreateCustomerRequest(

        @NotBlank(message = "First name is required")
        @Size(max = 100, message = "First name must be at most 100 characters")
        @Pattern(regexp = "^\\p{L}[\\p{L} .'-]*$", message = "First name may contain only letters, spaces, . ' and -")
        String firstName,

        @NotBlank(message = "Last name is required")
        @Size(max = 100, message = "Last name must be at most 100 characters")
        @Pattern(regexp = "^\\p{L}[\\p{L} .'-]*$", message = "Last name may contain only letters, spaces, . ' and -")
        String lastName,

        @NotBlank(message = "Email is required")
        @Size(max = 255, message = "Email must be at most 255 characters")
        @Email(message = "Email must be a valid address")
        @Pattern(regexp = "^[^@\\s]+@[^@\\s]+\\.[^@\\s]+$", message = "Email must be a valid address")
        String email,

        @Pattern(regexp = "^\\+?[0-9]{7,15}$", message = "Phone must be 7 to 15 digits, optionally starting with +")
        String phone) {
}
