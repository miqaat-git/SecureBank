package com.securebank.customer.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.InputStream;
import java.net.URL;
import java.util.Collections;
import java.util.List;
import java.util.Properties;

import org.junit.jupiter.api.Test;

/**
 * Reads the real production application.properties (not the test one) and checks the safety-critical
 * settings, without starting Spring or touching any database.
 */
class ProductionConfigurationTest {

    private static Properties loadProductionProperties() throws IOException {
        List<URL> resources = Collections.list(
                ProductionConfigurationTest.class.getClassLoader().getResources("application.properties"));
        URL production = resources.stream()
                .filter(url -> !url.toString().contains("test-classes"))
                .findFirst()
                .orElseThrow(() -> new AssertionError("production application.properties not found on classpath"));
        Properties properties = new Properties();
        try (InputStream in = production.openStream()) {
            properties.load(in);
        }
        return properties;
    }

    @Test
    void schemaIsValidatedNeverGenerated() throws IOException {
        Properties p = loadProductionProperties();

        assertThat(p.getProperty("spring.jpa.hibernate.ddl-auto")).isEqualTo("validate");
        assertThat(p.getProperty("spring.flyway.enabled")).isEqualTo("true");
    }

    @Test
    void servicePortIs8081() throws IOException {
        assertThat(loadProductionProperties().getProperty("server.port")).isEqualTo("8081");
    }

    @Test
    void credentialsComeOnlyFromEnvironmentVariables() throws IOException {
        Properties p = loadProductionProperties();

        assertThat(p.getProperty("spring.datasource.username")).isEqualTo("${CUSTOMER_DB_USERNAME}");
        assertThat(p.getProperty("spring.datasource.password")).isEqualTo("${CUSTOMER_DB_PASSWORD}");
        assertThat(p.getProperty("spring.flyway.user")).isEqualTo("${CUSTOMER_DB_MIGRATION_USERNAME}");
        assertThat(p.getProperty("spring.flyway.password")).isEqualTo("${CUSTOMER_DB_MIGRATION_PASSWORD}");
    }

    @Test
    void errorDetailsAreNotExposedAndDocsAreOffByDefault() throws IOException {
        Properties p = loadProductionProperties();

        assertThat(p.getProperty("server.error.include-stacktrace")).isEqualTo("never");
        assertThat(p.getProperty("server.error.include-message")).isEqualTo("never");
        assertThat(p.getProperty("securebank.security.public-api-docs")).isEqualTo("false");
        assertThat(p.getProperty("springdoc.api-docs.enabled")).isEqualTo("false");
        assertThat(p.getProperty("management.endpoints.web.exposure.include")).isEqualTo("health");
    }
}
