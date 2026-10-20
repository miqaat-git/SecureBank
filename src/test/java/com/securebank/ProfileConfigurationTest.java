package com.securebank;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

/**
 * Phase 0: configuration facts that must hold from the start (blueprint 3.1, 5.1). These are
 * plain file checks, so they run without a database and without starting the application.
 */
class ProfileConfigurationTest {

    private static Properties load(String name) throws IOException {
        Properties properties = new Properties();
        try (InputStream in = ProfileConfigurationTest.class.getClassLoader().getResourceAsStream(name)) {
            assertThat(in).as(name + " is on the classpath").isNotNull();
            properties.load(in);
        }
        return properties;
    }

    @Test
    void defaultsAreProductionSafe() throws IOException {
        Properties p = load("application.properties");
        assertThat(p.getProperty("server.servlet.session.cookie.secure")).isEqualTo("true");
        assertThat(p.getProperty("server.servlet.session.cookie.http-only")).isEqualTo("true");
        assertThat(p.getProperty("server.servlet.session.cookie.same-site")).isEqualTo("lax");
        assertThat(p.getProperty("spring.jpa.hibernate.ddl-auto")).isEqualTo("validate");
        assertThat(p.getProperty("spring.jpa.open-in-view")).isEqualTo("false");
        assertThat(p.getProperty("spring.flyway.enabled")).isEqualTo("false");
        assertThat(p.getProperty("server.error.include-stacktrace")).isEqualTo("never");
        assertThat(p.getProperty("spring.mvc.view.prefix")).isEqualTo("/WEB-INF/views/");
    }

    @Test
    void productionProfileKeepsSecureCookieValidationAndNoMigrations() throws IOException {
        Properties p = load("application-prod.properties");
        assertThat(p.getProperty("server.servlet.session.cookie.secure")).isEqualTo("true");
        assertThat(p.getProperty("spring.jpa.hibernate.ddl-auto")).isEqualTo("validate");
        assertThat(p.getProperty("spring.flyway.enabled")).isEqualTo("false");
    }

    @Test
    void secureCookieIsRelaxedOnlyInTheDevProfile() throws IOException {
        Properties dev = load("application-dev.properties");
        assertThat(dev.getProperty("server.servlet.session.cookie.secure")).isEqualTo("false");
        assertThat(dev.getProperty("server.address")).isEqualTo("127.0.0.1");
        // HttpOnly and SameSite are not overridden anywhere.
        assertThat(dev.getProperty("server.servlet.session.cookie.http-only")).isNull();
        assertThat(dev.getProperty("server.servlet.session.cookie.same-site")).isNull();
    }

    @Test
    void noRuntimeConfigurationContainsMigrationCredentialsOrLiteralPasswords() throws IOException {
        Path resources = Path.of("src/main/resources");
        assertThat(resources).isDirectory();
        try (Stream<Path> files = Files.walk(resources)) {
            for (Path file : (Iterable<Path>) files.filter(Files::isRegularFile)::iterator) {
                String text = Files.readString(file);
                assertThat(text).as(file + " mentions migration credentials")
                        .doesNotContainIgnoringCase("SB_MIGRATE")
                        .doesNotContainIgnoringCase("flyway.user")
                        .doesNotContainIgnoringCase("flyway.password");
                for (String line : text.split("\\R")) {
                    String trimmed = line.trim();
                    if (trimmed.startsWith("#") || !trimmed.toLowerCase().contains("password")) {
                        continue;
                    }
                    assertThat(trimmed).as(file + " must take passwords from the environment")
                            .contains("${");
                }
            }
        }
    }
}
