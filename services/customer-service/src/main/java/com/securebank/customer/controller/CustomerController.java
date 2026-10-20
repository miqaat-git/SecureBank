package com.securebank.customer.controller;

import com.securebank.customer.dto.ChangeStatusRequest;
import com.securebank.customer.dto.CreateCustomerRequest;
import com.securebank.customer.dto.CustomerResponse;
import com.securebank.customer.dto.CustomerStatusResponse;
import com.securebank.customer.dto.PageResponse;
import com.securebank.customer.dto.UpdateCustomerRequest;
import com.securebank.customer.service.CustomerService;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;

import java.net.URI;
import java.util.UUID;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.support.ServletUriComponentsBuilder;

/** REST API for customers. Delegates every operation to CustomerService. */
@RestController
@RequestMapping("/api/v1/customers")
@Tag(name = "Customers", description = "Customer management (fictional customers only)")
public class CustomerController {

    private final CustomerService customerService;

    public CustomerController(CustomerService customerService) {
        this.customerService = customerService;
    }

    @PostMapping
    @Operation(summary = "Create a customer")
    public ResponseEntity<CustomerResponse> create(@Valid @RequestBody CreateCustomerRequest request) {
        CustomerResponse created = customerService.create(request);
        URI location = ServletUriComponentsBuilder.fromCurrentRequest()
                .path("/{id}")
                .buildAndExpand(created.id())
                .toUri();
        return ResponseEntity.created(location).body(created);
    }

    @GetMapping("/{id}")
    @Operation(summary = "Get a customer by id")
    public CustomerResponse getById(@PathVariable UUID id) {
        return customerService.getById(id);
    }

    @GetMapping
    @Operation(summary = "List customers (paginated, sortable)")
    public PageResponse<CustomerResponse> list(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size,
            @RequestParam(defaultValue = "createdAt") String sortBy,
            @RequestParam(defaultValue = "desc") String direction) {
        return customerService.list(page, size, sortBy, direction);
    }

    @GetMapping("/search")
    @Operation(summary = "Search customers by name or email (paginated)")
    public PageResponse<CustomerResponse> search(
            @RequestParam("q") String query,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size,
            @RequestParam(defaultValue = "lastName") String sortBy,
            @RequestParam(defaultValue = "asc") String direction) {
        return customerService.search(query, page, size, sortBy, direction);
    }

    @PutMapping("/{id}")
    @Operation(summary = "Update a customer's profile (first name, last name, email, phone)")
    public CustomerResponse update(@PathVariable UUID id, @Valid @RequestBody UpdateCustomerRequest request) {
        return customerService.update(id, request);
    }

    @GetMapping("/{id}/status")
    @Operation(summary = "Get a customer's status")
    public CustomerStatusResponse getStatus(@PathVariable UUID id) {
        return customerService.getStatus(id);
    }

    @PatchMapping("/{id}/status")
    @Operation(summary = "Activate or suspend a customer (administrators only)")
    public CustomerStatusResponse changeStatus(@PathVariable UUID id, @Valid @RequestBody ChangeStatusRequest request) {
        return customerService.changeStatus(id, request);
    }
}
