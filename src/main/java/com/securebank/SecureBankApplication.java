package com.securebank;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.web.servlet.support.SpringBootServletInitializer;

/**
 * Entry point. Extending {@link SpringBootServletInitializer} lets the same WAR deploy to a
 * standalone Tomcat 10.1, while main() runs it with embedded Tomcat for development and tests.
 *
 * SecureBank is an educational banking simulator with fictional customers and simulated funds.
 */
@SpringBootApplication
public class SecureBankApplication extends SpringBootServletInitializer {

    @Override
    protected SpringApplicationBuilder configure(SpringApplicationBuilder builder) {
        return builder.sources(SecureBankApplication.class);
    }

    public static void main(String[] args) {
        SpringApplication.run(SecureBankApplication.class, args);
    }
}
