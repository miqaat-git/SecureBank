package com.securebank.customer.repository;

import com.securebank.customer.entity.Customer;

import java.util.Optional;
import java.util.UUID;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface CustomerRepository extends JpaRepository<Customer, UUID> {

    Optional<Customer> findByEmail(String email);

    Optional<Customer> findByCustomerNumber(String customerNumber);

    boolean existsByEmail(String email);

    boolean existsByEmailAndIdNot(String email, UUID id);

    boolean existsByCustomerNumber(String customerNumber);

    /**
     * Case-insensitive "contains" search over first name, last name, "first last" and email.
     * The pattern must already be lower case and have %, _ and ! escaped with '!'
     * (see CustomerService). '!' is used as ESCAPE because a backslash is special in MySQL.
     */
    @Query("""
            select c from Customer c
            where lower(c.firstName) like :pattern escape '!'
               or lower(c.lastName) like :pattern escape '!'
               or lower(concat(c.firstName, ' ', c.lastName)) like :pattern escape '!'
               or lower(c.email) like :pattern escape '!'
            """)
    Page<Customer> search(@Param("pattern") String pattern, Pageable pageable);
}
