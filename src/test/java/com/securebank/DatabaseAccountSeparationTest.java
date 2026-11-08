package com.securebank;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.output.MigrateResult;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.testcontainers.DockerClientFactory;
import org.testcontainers.containers.MySQLContainer;

import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Phase 0 check 4: the migration account can run Flyway, and the application account has only
 * the approved privileges. It runs against a real MySQL 8 and uses the same SQL template as
 * db/setup/create-db-accounts.sh, so the script's grants are what is tested.
 *
 * MySQL comes from one of two places:
 *  1. A local server, when SB_TEST_MYSQL_URL (for example jdbc:mysql://localhost:3306/),
 *     SB_TEST_MYSQL_ADMIN_USER and SB_TEST_MYSQL_ADMIN_PASSWORD are set. The test creates and
 *     drops its own throwaway schema and accounts. Use a development server only.
 *  2. A Testcontainers mysql:8.0 container, when Docker is available.
 * With neither, the tests are SKIPPED (reported as skipped, never as passed).
 */
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class DatabaseAccountSeparationTest {

    private static final String CONNECTION_OPTIONS = "?allowPublicKeyRetrieval=true&useSSL=false";
    // 1142 table access denied, 1044 database access denied, 1045 access denied, 1227 missing privilege
    private static final Set<Integer> DENIED = Set.of(1142, 1044, 1045, 1227);

    private static MySQLContainer<?> container;
    private static String baseUrl;       // jdbc:mysql://host:port/
    private static String adminUser;
    private static String adminPassword;

    private static String schema;
    private static String appUser;
    private static String migrateUser;
    private static String appPassword;
    private static String migratePassword;

    @BeforeAll
    static void startDatabaseAndCreateAccounts() throws Exception {
        String envUrl = System.getenv("SB_TEST_MYSQL_URL");
        if (envUrl != null && !envUrl.isBlank()) {
            baseUrl = envUrl.endsWith("/") ? envUrl : envUrl + "/";
            adminUser = System.getenv("SB_TEST_MYSQL_ADMIN_USER");
            adminPassword = System.getenv("SB_TEST_MYSQL_ADMIN_PASSWORD");
            assumeTrue(adminUser != null && adminPassword != null,
                    "SB_TEST_MYSQL_ADMIN_USER and SB_TEST_MYSQL_ADMIN_PASSWORD are required with SB_TEST_MYSQL_URL");
        } else {
            assumeTrue(DockerClientFactory.instance().isDockerAvailable(),
                    "Neither SB_TEST_MYSQL_URL nor Docker is available: database tests skipped");
            container = new MySQLContainer<>("mysql:8.0");
            container.start();
            baseUrl = "jdbc:mysql://" + container.getHost() + ":" + container.getMappedPort(3306) + "/";
            adminUser = "root";
            adminPassword = container.getPassword();
        }

        String suffix = UUID.randomUUID().toString().replace("-", "").substring(0, 10);
        schema = "sbp0" + suffix;                 // no '_' : it would be a wildcard in GRANT
        appUser = "sbapp" + suffix;
        migrateUser = "sbmig" + suffix;
        appPassword = "A" + UUID.randomUUID().toString().replace("-", "");
        migratePassword = "M" + UUID.randomUUID().toString().replace("-", "");

        String template = Files.readString(Path.of("db/setup/accounts.sql.template"));
        String sql = template
                .replace("@SCHEMA@", schema)
                .replace("@APP_USER@", appUser)
                .replace("@MIGRATE_USER@", migrateUser)
                .replace("@HOST@", "%")
                .replace("@APP_PASSWORD@", appPassword)
                .replace("@MIGRATE_PASSWORD@", migratePassword);
        try (Connection admin = connect(adminUser, adminPassword, ""); Statement st = admin.createStatement()) {
            for (String statement : statements(sql)) {
                st.execute(statement);
            }
        }
    }

    @AfterAll
    static void cleanUp() {
        if (baseUrl != null && schema != null) {
            try (Connection admin = connect(adminUser, adminPassword, ""); Statement st = admin.createStatement()) {
                st.execute("DROP DATABASE IF EXISTS `" + schema + "`");
                st.execute("DROP USER IF EXISTS '" + appUser + "'@'%'");
                st.execute("DROP USER IF EXISTS '" + migrateUser + "'@'%'");
            } catch (SQLException ignored) {
                // best effort: the schema and accounts are throwaway
            }
        }
        if (container != null) {
            container.stop();
        }
    }

    @Test
    @Order(1)
    void migrationAccountCanRunFlywayMigrations() {
        Flyway flyway = Flyway.configure()
                .dataSource(baseUrl + schema + CONNECTION_OPTIONS, migrateUser, migratePassword)
                .locations("classpath:db/phase0-proof")
                .cleanDisabled(true)
                .load();
        MigrateResult result = flyway.migrate();
        assertThat(result.success).isTrue();
        assertThat(result.migrationsExecuted).isEqualTo(1);
    }

    @Test
    @Order(2)
    void applicationAccountCanSelectAndInsert() throws Exception {
        try (Connection app = connect(appUser, appPassword, schema); Statement st = app.createStatement()) {
            assertThat(st.executeUpdate("INSERT INTO phase0_probe (note) VALUES ('inserted by the application account')"))
                    .isEqualTo(1);
            try (ResultSet rs = st.executeQuery("SELECT COUNT(*) FROM phase0_probe")) {
                assertThat(rs.next()).isTrue();
                assertThat(rs.getInt(1)).isEqualTo(1);
            }
        }
    }

    @Test
    @Order(3)
    void applicationAccountCannotUpdateDeleteOrChangeTheSchema() throws Exception {
        String[] forbidden = {
            "UPDATE phase0_probe SET note = 'changed'",
            "DELETE FROM phase0_probe",
            "TRUNCATE TABLE phase0_probe",
            "CREATE TABLE phase0_should_not_exist (id INT)",
            "ALTER TABLE phase0_probe ADD COLUMN extra INT",
            "DROP TABLE phase0_probe",
            "CREATE INDEX phase0_idx ON phase0_probe (note)",
        };
        try (Connection app = connect(appUser, appPassword, schema); Statement st = app.createStatement()) {
            for (String sql : forbidden) {
                assertDenied(st, sql);
            }
        }
        // Nothing changed: the table and its one row are intact.
        try (Connection migrate = connect(migrateUser, migratePassword, schema);
             Statement st = migrate.createStatement();
             ResultSet rs = st.executeQuery("SELECT COUNT(*) FROM phase0_probe")) {
            assertThat(rs.next()).isTrue();
            assertThat(rs.getInt(1)).isEqualTo(1);
        }
    }

    @Test
    @Order(4)
    void applicationAccountHoldsExactlyTheApprovedGrant() throws Exception {
        List<String> grants = grantsFor(appUser);
        grants.removeIf(g -> g.startsWith("GRANT USAGE ON *.*"));
        assertThat(grants).hasSize(1);
        assertThat(grants.get(0)).startsWith("GRANT SELECT, INSERT ON `" + schema + "`.*");
        assertThat(grants.get(0)).doesNotContain("UPDATE", "DELETE", "DROP", "ALTER", "CREATE", "ALL PRIVILEGES", "GRANT OPTION");
    }

    @Test
    @Order(5)
    void migrationAccountIsConfinedToItsOwnSchema() throws Exception {
        try (Connection migrate = connect(migrateUser, migratePassword, schema); Statement st = migrate.createStatement()) {
            assertDenied(st, "CREATE DATABASE sbp0evil" + schema.substring(4));
            assertDenied(st, "SELECT user FROM mysql.user");
        }
        List<String> grants = grantsFor(migrateUser);
        grants.removeIf(g -> g.startsWith("GRANT USAGE ON *.*"));
        assertThat(grants).hasSize(1);
        assertThat(grants.get(0)).contains("ON `" + schema + "`.*").doesNotContain("ALL PRIVILEGES", "GRANT OPTION", "ON *.*");
    }

    // ---------------------------------------------------------------- helpers

    private static Connection connect(String user, String password, String database) throws SQLException {
        return DriverManager.getConnection(baseUrl + database + CONNECTION_OPTIONS, user, password);
    }

    private static void assertDenied(Statement st, String sql) {
        try {
            st.execute(sql);
        } catch (SQLException e) {
            assertThat(DENIED).as("error code %d for: %s (%s)", e.getErrorCode(), sql, e.getMessage())
                    .contains(e.getErrorCode());
            return;
        }
        throw new AssertionError("Statement was not refused: " + sql);
    }

    private static List<String> grantsFor(String user) throws SQLException {
        List<String> grants = new ArrayList<>();
        try (Connection admin = connect(adminUser, adminPassword, "");
             Statement st = admin.createStatement();
             ResultSet rs = st.executeQuery("SHOW GRANTS FOR '" + user + "'@'%'")) {
            while (rs.next()) {
                grants.add(rs.getString(1));
            }
        }
        return grants;
    }

    /** Removes "--" comment lines and splits on semicolons. The template has no semicolons inside values. */
    private static List<String> statements(String sql) {
        StringBuilder cleaned = new StringBuilder();
        for (String line : sql.split("\\R")) {
            if (!line.trim().startsWith("--")) {
                cleaned.append(line).append('\n');
            }
        }
        List<String> result = new ArrayList<>();
        for (String part : cleaned.toString().split(";")) {
            if (!part.isBlank()) {
                result.add(part.trim());
            }
        }
        return result;
    }
}
