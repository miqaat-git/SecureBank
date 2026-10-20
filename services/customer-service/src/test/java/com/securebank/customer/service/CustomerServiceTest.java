package com.securebank.customer.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.securebank.customer.dto.ChangeStatusRequest;
import com.securebank.customer.dto.CreateCustomerRequest;
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

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;

/** Unit tests for the business rules; the repository is mocked, so no database is involved. */
@ExtendWith(MockitoExtension.class)
class CustomerServiceTest {

    @Mock
    private CustomerRepository repository;

    private CustomerService service;

    @BeforeEach
    void setUp() {
        service = new CustomerService(repository);
    }

    private static Customer existing(Customer.Status status) {
        Customer customer = new Customer("CUS000000000001", "Asha", "Verma", "asha@example.test", null);
        customer.setStatus(status);
        return customer;
    }

    // ---- create -----------------------------------------------------------

    @Test
    void createNormalizesEmailTrimsNamesAndGeneratesCustomerNumber() {
        when(repository.saveAndFlush(any(Customer.class))).thenAnswer(inv -> inv.getArgument(0));

        CustomerResponse response = service.create(
                new CreateCustomerRequest("  Asha ", " Verma  ", "Asha.Verma@Example.TEST", "+919000000001"));

        ArgumentCaptor<Customer> captor = ArgumentCaptor.forClass(Customer.class);
        verify(repository).saveAndFlush(captor.capture());
        Customer saved = captor.getValue();
        assertThat(saved.getEmail()).isEqualTo("asha.verma@example.test");
        assertThat(saved.getFirstName()).isEqualTo("Asha");
        assertThat(saved.getLastName()).isEqualTo("Verma");
        assertThat(saved.getStatus()).isEqualTo(Customer.Status.ACTIVE);
        assertThat(saved.getCustomerNumber()).matches("CUS\\d{12}");
        assertThat(response.email()).isEqualTo("asha.verma@example.test");
        assertThat(response.status()).isEqualTo(Customer.Status.ACTIVE);
    }

    @Test
    void createTurnsBlankPhoneIntoNull() {
        when(repository.saveAndFlush(any(Customer.class))).thenAnswer(inv -> inv.getArgument(0));

        CustomerResponse response = service.create(new CreateCustomerRequest("Asha", "Verma", "a@example.test", "  "));

        assertThat(response.phone()).isNull();
    }

    @Test
    void createRejectsDuplicateEmailWithoutSaving() {
        when(repository.existsByEmail("asha@example.test")).thenReturn(true);

        assertThatThrownBy(() -> service.create(
                new CreateCustomerRequest("Asha", "Verma", "ASHA@example.test", null)))
                .isInstanceOf(DuplicateEmailException.class);
        verify(repository, never()).saveAndFlush(any());
    }

    @Test
    void createReportsDatabaseUniquenessRaceAsDuplicateEmail() {
        when(repository.existsByEmail("race@example.test")).thenReturn(false, true);
        when(repository.saveAndFlush(any(Customer.class))).thenThrow(new DataIntegrityViolationException("unique"));

        assertThatThrownBy(() -> service.create(new CreateCustomerRequest("Asha", "Verma", "race@example.test", null)))
                .isInstanceOf(DuplicateEmailException.class);
    }

    @Test
    void createRethrowsOtherIntegrityViolations() {
        when(repository.saveAndFlush(any(Customer.class))).thenThrow(new DataIntegrityViolationException("other"));

        assertThatThrownBy(() -> service.create(new CreateCustomerRequest("Asha", "Verma", "x@example.test", null)))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    // ---- read -------------------------------------------------------------

    @Test
    void getByIdReturnsMappedCustomer() {
        UUID id = UUID.randomUUID();
        when(repository.findById(id)).thenReturn(Optional.of(existing(Customer.Status.ACTIVE)));

        assertThat(service.getById(id).email()).isEqualTo("asha@example.test");
    }

    @Test
    void getByIdThrowsWhenMissing() {
        UUID id = UUID.randomUUID();
        when(repository.findById(id)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.getById(id)).isInstanceOf(CustomerNotFoundException.class);
    }

    @Test
    void getStatusReturnsStatusOnly() {
        UUID id = UUID.randomUUID();
        when(repository.findById(id)).thenReturn(Optional.of(existing(Customer.Status.SUSPENDED)));

        CustomerStatusResponse status = service.getStatus(id);

        assertThat(status.status()).isEqualTo(Customer.Status.SUSPENDED);
    }

    // ---- list / search ----------------------------------------------------

    @Test
    void listBuildsPageableWithIdTieBreaker() {
        when(repository.findAll(any(Pageable.class))).thenReturn(new PageImpl<>(List.of(existing(Customer.Status.ACTIVE))));

        PageResponse<CustomerResponse> page = service.list(2, 10, "lastName", "ASC");

        ArgumentCaptor<Pageable> captor = ArgumentCaptor.forClass(Pageable.class);
        verify(repository).findAll(captor.capture());
        Pageable pageable = captor.getValue();
        assertThat(pageable.getPageNumber()).isEqualTo(2);
        assertThat(pageable.getPageSize()).isEqualTo(10);
        assertThat(pageable.getSort().getOrderFor("lastName")).isNotNull();
        assertThat(pageable.getSort().getOrderFor("id")).isNotNull();
        assertThat(page.content()).hasSize(1);
    }

    @Test
    void listRejectsInvalidPagingAndSortingParameters() {
        assertThatThrownBy(() -> service.list(-1, 20, "createdAt", "desc")).isInstanceOf(InvalidRequestException.class);
        assertThatThrownBy(() -> service.list(0, 0, "createdAt", "desc")).isInstanceOf(InvalidRequestException.class);
        assertThatThrownBy(() -> service.list(0, 101, "createdAt", "desc")).isInstanceOf(InvalidRequestException.class);
        assertThatThrownBy(() -> service.list(0, 20, "password", "desc")).isInstanceOf(InvalidRequestException.class);
        assertThatThrownBy(() -> service.list(0, 20, "createdAt", "sideways")).isInstanceOf(InvalidRequestException.class);
        verify(repository, never()).findAll(any(Pageable.class));
    }

    @Test
    void searchLowercasesAndEscapesWildcards() {
        when(repository.search(anyString(), any(Pageable.class))).thenReturn(Page.empty());

        service.search("  50%_Off!  ", 0, 20, "lastName", "asc");

        verify(repository).search(eq("%50!%!_off!!%"), any(Pageable.class));
    }

    @Test
    void searchRejectsBlankAndOverlongText() {
        assertThatThrownBy(() -> service.search("   ", 0, 20, "lastName", "asc"))
                .isInstanceOf(InvalidRequestException.class);
        assertThatThrownBy(() -> service.search(null, 0, 20, "lastName", "asc"))
                .isInstanceOf(InvalidRequestException.class);
        assertThatThrownBy(() -> service.search("x".repeat(101), 0, 20, "lastName", "asc"))
                .isInstanceOf(InvalidRequestException.class);
    }

    // ---- update -----------------------------------------------------------

    @Test
    void updateChangesProfileFields() {
        UUID id = UUID.randomUUID();
        Customer customer = existing(Customer.Status.ACTIVE);
        when(repository.findById(id)).thenReturn(Optional.of(customer));
        when(repository.existsByEmailAndIdNot("new@example.test", id)).thenReturn(false);
        when(repository.saveAndFlush(customer)).thenReturn(customer);

        CustomerResponse response = service.update(id,
                new UpdateCustomerRequest("Anita", "Rao", "New@Example.test", "+911234567890"));

        assertThat(response.firstName()).isEqualTo("Anita");
        assertThat(response.lastName()).isEqualTo("Rao");
        assertThat(response.email()).isEqualTo("new@example.test");
        assertThat(response.phone()).isEqualTo("+911234567890");
    }

    @Test
    void updateRejectsEmailUsedByAnotherCustomer() {
        UUID id = UUID.randomUUID();
        when(repository.findById(id)).thenReturn(Optional.of(existing(Customer.Status.ACTIVE)));
        when(repository.existsByEmailAndIdNot("taken@example.test", id)).thenReturn(true);

        assertThatThrownBy(() -> service.update(id, new UpdateCustomerRequest("A", "B", "taken@example.test", null)))
                .isInstanceOf(DuplicateEmailException.class);
        verify(repository, never()).saveAndFlush(any());
    }

    @Test
    void updateRejectsClosedCustomer() {
        UUID id = UUID.randomUUID();
        when(repository.findById(id)).thenReturn(Optional.of(existing(Customer.Status.CLOSED)));

        assertThatThrownBy(() -> service.update(id, new UpdateCustomerRequest("A", "B", "a@example.test", null)))
                .isInstanceOf(InvalidCustomerStateException.class);
    }

    @Test
    void updateThrowsWhenCustomerMissing() {
        UUID id = UUID.randomUUID();
        when(repository.findById(id)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.update(id, new UpdateCustomerRequest("A", "B", "a@example.test", null)))
                .isInstanceOf(CustomerNotFoundException.class);
    }

    // ---- status -----------------------------------------------------------

    @Test
    void changeStatusSuspendsAnActiveCustomer() {
        UUID id = UUID.randomUUID();
        Customer customer = existing(Customer.Status.ACTIVE);
        when(repository.findById(id)).thenReturn(Optional.of(customer));
        when(repository.saveAndFlush(customer)).thenReturn(customer);

        CustomerStatusResponse response = service.changeStatus(id, new ChangeStatusRequest(Customer.Status.SUSPENDED));

        assertThat(response.status()).isEqualTo(Customer.Status.SUSPENDED);
        verify(repository).saveAndFlush(customer);
    }

    @Test
    void changeStatusIsIdempotentWhenAlreadyInTargetStatus() {
        UUID id = UUID.randomUUID();
        when(repository.findById(id)).thenReturn(Optional.of(existing(Customer.Status.ACTIVE)));

        CustomerStatusResponse response = service.changeStatus(id, new ChangeStatusRequest(Customer.Status.ACTIVE));

        assertThat(response.status()).isEqualTo(Customer.Status.ACTIVE);
        verify(repository, never()).saveAndFlush(any());
    }

    @Test
    void changeStatusCannotSetClosed() {
        UUID id = UUID.randomUUID();

        assertThatThrownBy(() -> service.changeStatus(id, new ChangeStatusRequest(Customer.Status.CLOSED)))
                .isInstanceOf(InvalidRequestException.class);
        verify(repository, never()).findById(any());
    }

    @Test
    void changeStatusCannotReopenClosedCustomer() {
        UUID id = UUID.randomUUID();
        when(repository.findById(id)).thenReturn(Optional.of(existing(Customer.Status.CLOSED)));

        assertThatThrownBy(() -> service.changeStatus(id, new ChangeStatusRequest(Customer.Status.ACTIVE)))
                .isInstanceOf(InvalidCustomerStateException.class);
    }

    @Test
    void changeStatusThrowsWhenCustomerMissing() {
        UUID id = UUID.randomUUID();
        when(repository.findById(id)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.changeStatus(id, new ChangeStatusRequest(Customer.Status.ACTIVE)))
                .isInstanceOf(CustomerNotFoundException.class);
    }
}
