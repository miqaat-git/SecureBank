package com.securebank.customer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.env.Environment;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtException;
import org.springframework.security.web.SecurityFilterChain;

import com.securebank.customer.repository.CustomerRepository;
import com.securebank.customer.service.CustomerService;

/** Application startup and wiring checks (isolated H2 database, no MySQL). */
@SpringBootTest
class CustomerServiceApplicationTests {

    @Autowired
    private CustomerService customerService;

    @Autowired
    private CustomerRepository customerRepository;

    @Autowired
    private SecurityFilterChain securityFilterChain;

    @Autowired
    private JwtDecoder jwtDecoder;

    @Autowired
    private Environment environment;

    @Test
    void contextLoadsWithAllBeansWired() {
        assertThat(customerService).isNotNull();
        assertThat(customerRepository).isNotNull();
        assertThat(securityFilterChain).isNotNull();
    }

    @Test
    void testsRunOnIsolatedH2NotMySql() {
        String url = environment.getProperty("spring.datasource.url");

        assertThat(url).startsWith("jdbc:h2:mem:");
        assertThat(url).doesNotContain("securebank_customer");
        assertThat(environment.getProperty("spring.flyway.enabled")).isEqualTo("false");
    }

    @Test
    void withoutConfiguredIssuerEveryTokenIsRejected() {
        assertThatThrownBy(() -> jwtDecoder.decode("not-a-real-token")).isInstanceOf(JwtException.class);
    }
}
