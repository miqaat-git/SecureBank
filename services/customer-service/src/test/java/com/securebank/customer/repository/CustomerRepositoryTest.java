package com.securebank.customer.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.securebank.customer.entity.Customer;

import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.autoconfigure.orm.jpa.TestEntityManager;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;

/**
 * Repository tests on the isolated H2 database from src/test/resources/application.properties.
 * Each test runs in a transaction that is rolled back, so tests are independent of each other.
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
class CustomerRepositoryTest {

    @Autowired
    private CustomerRepository customerRepository;

    @Autowired
    private TestEntityManager entityManager;

    private Customer saveAndReload(Customer customer) {
        UUID id = entityManager.persistFlushFind(customer).getId();
        entityManager.clear();
        return customerRepository.findById(id).orElseThrow();
    }

    private static Customer customer(String number, String email) {
        return new Customer(number, "Asha", "Verma", email, "+919000000001");
    }

    @Test
    void persistsAndRetrievesCustomerWithGeneratedUuid() {
        Customer found = saveAndReload(customer("CUS000000000001", "asha@example.test"));

        assertThat(found.getId()).isNotNull();
        assertThat(found.getCustomerNumber()).isEqualTo("CUS000000000001");
        assertThat(found.getFirstName()).isEqualTo("Asha");
        assertThat(found.getLastName()).isEqualTo("Verma");
        assertThat(found.getEmail()).isEqualTo("asha@example.test");
        assertThat(found.getPhone()).isEqualTo("+919000000001");
    }

    @Test
    void newCustomerIsActiveWithDatabaseTimestamps() {
        Customer found = saveAndReload(customer("CUS000000000002", "ravi@example.test"));

        assertThat(found.getStatus()).isEqualTo(Customer.Status.ACTIVE);
        assertThat(found.getCreatedAt()).isNotNull();
        assertThat(found.getUpdatedAt()).isNotNull();
    }

    @Test
    void phoneIsOptional() {
        Customer found = saveAndReload(new Customer("CUS000000000003", "Meera", "Iyer", "meera@example.test", null));

        assertThat(found.getPhone()).isNull();
    }

    @Test
    void findsByEmailAndByCustomerNumber() {
        customerRepository.saveAndFlush(customer("CUS000000000004", "kabir@example.test"));

        assertThat(customerRepository.findByEmail("kabir@example.test")).isPresent();
        assertThat(customerRepository.findByEmail("nobody@example.test")).isEmpty();
        assertThat(customerRepository.findByCustomerNumber("CUS000000000004")).isPresent();
        assertThat(customerRepository.findByCustomerNumber("CUS999999999999")).isEmpty();
    }

    @Test
    void existsMethodsReportCorrectly() {
        Customer saved = customerRepository.saveAndFlush(customer("CUS000000000005", "om@example.test"));

        assertThat(customerRepository.existsByEmail("om@example.test")).isTrue();
        assertThat(customerRepository.existsByEmail("nobody@example.test")).isFalse();
        assertThat(customerRepository.existsByCustomerNumber("CUS000000000005")).isTrue();
        assertThat(customerRepository.existsByEmailAndIdNot("om@example.test", saved.getId())).isFalse();
        assertThat(customerRepository.existsByEmailAndIdNot("om@example.test", UUID.randomUUID())).isTrue();
    }

    @Test
    void statusIsStoredAsEnumNameText() {
        Customer customer = customer("CUS000000000006", "tara@example.test");
        customer.setStatus(Customer.Status.SUSPENDED);
        Customer saved = customerRepository.saveAndFlush(customer);

        Object raw = entityManager.getEntityManager()
                .createNativeQuery("SELECT status FROM customers WHERE customer_id = ?1")
                .setParameter(1, saved.getId().toString())
                .getSingleResult();

        assertThat(raw).isEqualTo("SUSPENDED");
    }

    @Test
    void updatePersistsChanges() {
        Customer saved = customerRepository.saveAndFlush(customer("CUS000000000007", "dev@example.test"));
        saved.updateProfile("Devendra", "Patel", "devendra@example.test", null);
        saved.setStatus(Customer.Status.CLOSED);
        customerRepository.saveAndFlush(saved);
        entityManager.clear();

        Customer found = customerRepository.findById(saved.getId()).orElseThrow();
        assertThat(found.getFirstName()).isEqualTo("Devendra");
        assertThat(found.getEmail()).isEqualTo("devendra@example.test");
        assertThat(found.getPhone()).isNull();
        assertThat(found.getStatus()).isEqualTo(Customer.Status.CLOSED);
        assertThat(found.getUpdatedAt()).isNotNull();
    }

    @Test
    void duplicateEmailIsRejectedByTheDatabase() {
        customerRepository.saveAndFlush(customer("CUS000000000008", "same@example.test"));

        assertThatThrownBy(() -> customerRepository.saveAndFlush(customer("CUS000000000009", "same@example.test")))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void duplicateCustomerNumberIsRejectedByTheDatabase() {
        customerRepository.saveAndFlush(customer("CUS000000000010", "one@example.test"));

        assertThatThrownBy(() -> customerRepository.saveAndFlush(customer("CUS000000000010", "two@example.test")))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void supportsPaginationAndSorting() {
        long before = customerRepository.count();
        customerRepository.save(new Customer("CUS000000000011", "A", "Charlie", "c@example.test", null));
        customerRepository.save(new Customer("CUS000000000012", "B", "Alpha", "a@example.test", null));
        customerRepository.saveAndFlush(new Customer("CUS000000000013", "C", "Bravo", "b@example.test", null));

        Page<Customer> page = customerRepository.findAll(PageRequest.of(0, 2, Sort.by("lastName")));

        assertThat(page.getContent()).hasSize(2);
        assertThat(page.getTotalElements()).isEqualTo(before + 3);
        assertThat(page.getContent().get(0).getLastName()).isLessThanOrEqualTo(page.getContent().get(1).getLastName());
    }

    @Test
    void searchMatchesNamesFullNameAndEmailIgnoringCase() {
        customerRepository.save(new Customer("CUS000000000014", "Priya", "Sharma", "priya.s@example.test", null));
        customerRepository.saveAndFlush(new Customer("CUS000000000015", "Rohan", "Mehta", "rohan@example.test", null));

        PageRequest paging = PageRequest.of(0, 10, Sort.by("lastName"));

        assertThat(customerRepository.search("%priya%", paging).getTotalElements()).isEqualTo(1);
        assertThat(customerRepository.search("%mehta%", paging).getTotalElements()).isEqualTo(1);
        assertThat(customerRepository.search("%priya sharma%", paging).getTotalElements()).isEqualTo(1);
        assertThat(customerRepository.search("%rohan@example%", paging).getTotalElements()).isEqualTo(1);
        assertThat(customerRepository.search("%nomatchhere%", paging).getTotalElements()).isZero();
    }
}
