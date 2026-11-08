package com.securebank.customer.service;

import com.securebank.customer.config.CorrelationIdFilter;
import com.securebank.customer.dto.ChangeStatusRequest;
import com.securebank.customer.dto.CustomerStatusAuditResponse;
import com.securebank.customer.entity.CustomerStatusAudit;
import com.securebank.customer.repository.CustomerStatusAuditRepository;
import java.time.LocalDateTime;
import com.securebank.customer.dto.CreateCustomerRequest;
import com.securebank.customer.dto.CustomerEligibilityResponse;
import com.securebank.customer.dto.CustomerResponse;
import com.securebank.customer.dto.CustomerStatusResponse;
import com.securebank.customer.dto.PageResponse;
import com.securebank.customer.dto.UpdateCustomerRequest;
import com.securebank.customer.entity.Customer;
import com.securebank.customer.exception.CustomerNotFoundException;
import com.securebank.customer.exception.DuplicateEmailException;
import com.securebank.customer.exception.InvalidCustomerStateException;
import com.securebank.customer.exception.InvalidRequestException;
import com.securebank.customer.repository.CustomerRepository;

import java.security.SecureRandom;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Business rules for customers. Controllers call this class; only this class calls the repository. */
@Service
@Transactional(readOnly = true)
public class CustomerService {

    public static final int MAX_PAGE_SIZE = 100;
    public static final int MAX_SEARCH_LENGTH = 100;

    /** Only these Java property names may be used for sorting. */
    private static final Set<String> SORTABLE_FIELDS =
            Set.of("firstName", "lastName", "email", "status", "createdAt", "updatedAt");

    private static final int CUSTOMER_NUMBER_ATTEMPTS = 5;

    private final CustomerRepository customerRepository;
    private final CustomerStatusAuditRepository auditRepository;
    private final java.time.Clock clock;
    private final SecureRandom random = new SecureRandom();

    @org.springframework.beans.factory.annotation.Autowired
    public CustomerService(CustomerRepository customerRepository, CustomerStatusAuditRepository auditRepository) {
        this(customerRepository, auditRepository, java.time.Clock.systemDefaultZone());
    }

    CustomerService(CustomerRepository customerRepository, CustomerStatusAuditRepository auditRepository,
                    java.time.Clock clock) {
        this.customerRepository = customerRepository;
        this.auditRepository = auditRepository;
        this.clock = clock;
    }

    @Transactional
    public CustomerResponse create(CreateCustomerRequest request) {
        String email = normalizeEmail(request.email());
        if (customerRepository.existsByEmail(email)) {
            throw new DuplicateEmailException();
        }
        Customer customer = new Customer(
                generateCustomerNumber(),
                request.firstName().trim(),
                request.lastName().trim(),
                email,
                normalizePhone(request.phone()));
        return CustomerMapper.toResponse(saveOrReportDuplicate(customer, email));
    }

    public CustomerResponse getById(UUID id) {
        return CustomerMapper.toResponse(findCustomer(id));
    }

    public PageResponse<CustomerResponse> list(int page, int size, String sortBy, String direction) {
        Pageable pageable = buildPageable(page, size, sortBy, direction);
        Page<Customer> result = customerRepository.findAll(pageable);
        return PageResponse.from(result.map(CustomerMapper::toResponse));
    }

    public PageResponse<CustomerResponse> search(String query, int page, int size, String sortBy, String direction) {
        if (query == null || query.isBlank()) {
            throw new InvalidRequestException("Search text must not be blank.");
        }
        String trimmed = query.trim();
        if (trimmed.length() > MAX_SEARCH_LENGTH) {
            throw new InvalidRequestException("Search text must be at most " + MAX_SEARCH_LENGTH + " characters.");
        }
        Pageable pageable = buildPageable(page, size, sortBy, direction);
        String pattern = "%" + escapeLike(trimmed.toLowerCase(Locale.ROOT)) + "%";
        Page<Customer> result = customerRepository.search(pattern, pageable);
        return PageResponse.from(result.map(CustomerMapper::toResponse));
    }

    @Transactional
    public CustomerResponse update(UUID id, UpdateCustomerRequest request) {
        Customer customer = findCustomer(id);
        if (customer.getStatus() == Customer.Status.CLOSED) {
            throw new InvalidCustomerStateException("A closed customer cannot be modified.");
        }
        String email = normalizeEmail(request.email());
        if (customerRepository.existsByEmailAndIdNot(email, id)) {
            throw new DuplicateEmailException();
        }
        customer.updateProfile(
                request.firstName().trim(),
                request.lastName().trim(),
                email,
                normalizePhone(request.phone()));
        return CustomerMapper.toResponse(saveOrReportDuplicate(customer, email));
    }

    /** Minimal projection for the Account Service: id and status only, no personal data. */
    public CustomerEligibilityResponse getEligibility(UUID id) {
        Customer customer = findCustomer(id);
        return new CustomerEligibilityResponse(customer.getId(), customer.getStatus().name());
    }

    public CustomerStatusResponse getStatus(UUID id) {
        return CustomerMapper.toStatusResponse(findCustomer(id));
    }

    /**
     * Administrative operation: activate or suspend (CLOSED can never be set or left through the API). Enforced here,
     * not only in the controller: the actor must be an ADMIN, the reason mandatory, the transition legal. The audit
     * row is inserted in the same transaction as the status change, so a rolled-back change leaves no audit row and
     * a rejected request never creates one.
     */
    @Transactional
    public CustomerStatusResponse changeStatus(UUID id, ChangeStatusRequest request, StatusActor actor) {
        requireAdmin(actor);
        String reason = validateReason(request.reason());
        Customer.Status target = request.status();
        if (target != Customer.Status.ACTIVE && target != Customer.Status.SUSPENDED) {
            throw new InvalidRequestException("Status can only be changed to ACTIVE or SUSPENDED.");
        }
        Customer customer = findCustomer(id);
        if (customer.getStatus() == Customer.Status.CLOSED) {
            throw new InvalidCustomerStateException("A closed customer cannot change status.");
        }
        if (customer.getStatus() != target) {
            Customer.Status previous = customer.getStatus();
            customer.setStatus(target);
            customer = customerRepository.saveAndFlush(customer);
            auditRepository.save(new CustomerStatusAudit(customer.getId(), previous, target, reason,
                    actor.subject(), actor.role(), org.slf4j.MDC.get(CorrelationIdFilter.MDC_KEY),
                    LocalDateTime.now(clock)));
        }
        return CustomerMapper.toStatusResponse(customer);
    }

    /** Audit history of one customer, oldest first. ADMIN only (checked here as well as in the URL rules). */
    public PageResponse<CustomerStatusAuditResponse> statusAudit(UUID id, int page, int size, StatusActor actor) {
        requireAdmin(actor);
        if (page < 0) {
            throw new InvalidRequestException("Page must be 0 or greater.");
        }
        if (size < 1 || size > MAX_PAGE_SIZE) {
            throw new InvalidRequestException("Size must be between 1 and " + MAX_PAGE_SIZE + ".");
        }
        findCustomer(id);
        Page<CustomerStatusAudit> result = auditRepository.findByCustomerId(id,
                PageRequest.of(page, size, Sort.by(Sort.Direction.ASC, "id")));
        return PageResponse.from(result.map(CustomerStatusAuditResponse::from));
    }

    private static void requireAdmin(StatusActor actor) {
        if (actor == null || !actor.admin() || actor.subject() == null || actor.subject().isBlank()) {
            throw new org.springframework.security.access.AccessDeniedException("Administrator role required.");
        }
    }

    /** Non-blank, at most 255 characters, no control characters; returned trimmed. */
    static String validateReason(String reason) {
        if (reason == null || reason.isBlank()) {
            throw new InvalidRequestException("A reason is required.");
        }
        String trimmed = reason.trim();
        if (trimmed.length() > 255) {
            throw new InvalidRequestException("The reason must be at most 255 characters.");
        }
        if (trimmed.chars().anyMatch(Character::isISOControl)) {
            throw new InvalidRequestException("The reason must not contain control characters.");
        }
        return trimmed;
    }

    // ---- helpers ----------------------------------------------------------

    private Customer findCustomer(UUID id) {
        return customerRepository.findById(id).orElseThrow(() -> new CustomerNotFoundException(id));
    }

    /** Saves and flushes now so a database uniqueness race is reported as a clean duplicate-email error. */
    private Customer saveOrReportDuplicate(Customer customer, String email) {
        try {
            return customerRepository.saveAndFlush(customer);
        } catch (DataIntegrityViolationException ex) {
            if (customerRepository.existsByEmail(email)) {
                throw new DuplicateEmailException();
            }
            throw ex;
        }
    }

    private Pageable buildPageable(int page, int size, String sortBy, String direction) {
        if (page < 0) {
            throw new InvalidRequestException("Page must be 0 or greater.");
        }
        if (size < 1 || size > MAX_PAGE_SIZE) {
            throw new InvalidRequestException("Size must be between 1 and " + MAX_PAGE_SIZE + ".");
        }
        if (sortBy == null || !SORTABLE_FIELDS.contains(sortBy)) {
            throw new InvalidRequestException("sortBy must be one of: " + String.join(", ", new java.util.TreeSet<>(SORTABLE_FIELDS)) + ".");
        }
        Sort.Direction sortDirection;
        if ("asc".equalsIgnoreCase(direction)) {
            sortDirection = Sort.Direction.ASC;
        } else if ("desc".equalsIgnoreCase(direction)) {
            sortDirection = Sort.Direction.DESC;
        } else {
            throw new InvalidRequestException("direction must be 'asc' or 'desc'.");
        }
        // The id is a stable tie-breaker so pages never overlap.
        Sort sort = Sort.by(sortDirection, sortBy).and(Sort.by(Sort.Direction.ASC, "id"));
        return PageRequest.of(page, size, sort);
    }

    static String normalizeEmail(String email) {
        return email.trim().toLowerCase(Locale.ROOT);
    }

    static String normalizePhone(String phone) {
        if (phone == null || phone.isBlank()) {
            return null;
        }
        return phone.trim();
    }

    /** Escapes LIKE wildcards using '!' (the ESCAPE character used in the repository query). */
    static String escapeLike(String value) {
        return value.replace("!", "!!").replace("%", "!%").replace("_", "!_");
    }

    /** "CUS" + 12 random digits (15 characters, fits VARCHAR(20)); retried if it already exists. */
    private String generateCustomerNumber() {
        for (int attempt = 0; attempt < CUSTOMER_NUMBER_ATTEMPTS; attempt++) {
            String candidate = "CUS" + String.format("%012d", random.nextLong(1_000_000_000_000L));
            if (!customerRepository.existsByCustomerNumber(candidate)) {
                return candidate;
            }
        }
        throw new IllegalStateException("Could not generate a unique customer number.");
    }
}
