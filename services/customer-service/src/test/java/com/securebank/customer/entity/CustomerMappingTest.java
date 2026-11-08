package com.securebank.customer.entity;

import static org.assertj.core.api.Assertions.assertThat;

import jakarta.persistence.Column;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.junit.jupiter.api.Test;

/**
 * Plain JUnit test (no Spring, no database): checks the JPA annotations against the real Flyway
 * migration files, and guards the already-applied V1 migration against accidental edits.
 */
class CustomerMappingTest {

    private static final String V1 = "db/migration/V1__create_customer_tables.sql";
    private static final String V2 = "db/migration/V2__migrate_customers_to_uuid_and_new_status_model.sql";

    /** SHA-256 of V1 with line endings normalised to \n (Flyway checksums are line-ending independent). */
    private static final String V1_SHA256 = "5b21399d83214d4acc73a1fb5647506b07b58589baea45567e931ab15f9c20f2";

    private record SqlColumn(Integer length, boolean notNull) {
    }

    @Test
    void appliedV1MigrationIsUnchanged() throws Exception {
        String normalised = readResource(V1).replace("\r\n", "\n").replace('\r', '\n');
        String hash = HexFormat.of().formatHex(
                MessageDigest.getInstance("SHA-256").digest(normalised.getBytes(StandardCharsets.UTF_8)));

        assertThat(hash).as("V1 was already applied to MySQL and must never be edited").isEqualTo(V1_SHA256);
    }

    @Test
    void entityIsMappedToCustomersTable() {
        assertThat(Customer.class.getAnnotation(Table.class).name()).isEqualTo("customers");
    }

    @Test
    void primaryKeyIsUuidStoredInCustomerIdColumn() throws Exception {
        Field id = Customer.class.getDeclaredField("id");

        assertThat(id.getType()).isEqualTo(UUID.class);
        assertThat(id.isAnnotationPresent(Id.class)).isTrue();
        assertThat(id.getAnnotation(GeneratedValue.class).strategy()).isEqualTo(GenerationType.UUID);
        assertThat(id.getAnnotation(Column.class).name()).isEqualTo("customer_id");
        assertThat(id.getAnnotation(Column.class).length()).isEqualTo(36);
        assertThat(readResource(V2)).contains("customer_id VARCHAR(36) NOT NULL");
    }

    @Test
    void statusSupportsExactlyActiveSuspendedClosedStoredAsString() throws Exception {
        Field status = Customer.class.getDeclaredField("status");

        assertThat(status.getAnnotation(Enumerated.class).value()).isEqualTo(EnumType.STRING);
        assertThat(Arrays.stream(Customer.Status.values()).map(Enum::name))
                .containsExactly("ACTIVE", "SUSPENDED", "CLOSED");
        assertThat(readResource(V2)).contains("CHECK (status IN ('ACTIVE', 'SUSPENDED', 'CLOSED'))");
    }

    @Test
    void newCustomerDefaultsToActive() {
        Customer customer = new Customer("CUS000000000001", "Asha", "Verma", "asha@example.test", null);

        assertThat(customer.getStatus()).isEqualTo(Customer.Status.ACTIVE);
    }

    @Test
    void timestampsAreDatabaseManagedAndReadOnlyForJava() throws Exception {
        for (String name : new String[] { "createdAt", "updatedAt" }) {
            Column column = Customer.class.getDeclaredField(name).getAnnotation(Column.class);

            assertThat(column.insertable()).as(name + " insertable").isFalse();
            assertThat(column.updatable()).as(name + " updatable").isFalse();
        }
    }

    @Test
    void entityHasNoSecretOrBalanceFields() {
        Set<String> forbidden = Set.of("password", "pin", "balance", "secret", "token", "card");

        for (Field field : Customer.class.getDeclaredFields()) {
            assertThat(forbidden).as("field " + field.getName()).doesNotContain(field.getName().toLowerCase());
        }
    }

    @Test
    void lengthsAndNullabilityOfV1ColumnsMatchTheEntity() throws IOException {
        Map<String, SqlColumn> v1Columns = readV1Columns();
        String[] sharedColumns = { "customer_number", "first_name", "last_name", "email", "phone", "status" };

        for (String name : sharedColumns) {
            Column column = columnAnnotation(name);
            SqlColumn sql = v1Columns.get(name);

            assertThat(sql).as("column " + name + " exists in V1").isNotNull();
            assertThat(column.nullable()).as(name + " nullable").isEqualTo(!sql.notNull());
            assertThat(column.length()).as(name + " length").isEqualTo(sql.length());
        }
    }

    @Test
    void everyEntityColumnIsKnownToTheMigrations() throws IOException {
        Set<String> entityColumns = new LinkedHashSet<>();
        for (Field field : Customer.class.getDeclaredFields()) {
            Column column = field.getAnnotation(Column.class);
            if (column != null) {
                entityColumns.add(column.name());
            }
        }
        // V1 columns minus the replaced "id", plus "customer_id" introduced by V2.
        Set<String> expected = new LinkedHashSet<>(readV1Columns().keySet());
        expected.remove("id");
        expected.add("customer_id");

        assertThat(entityColumns).containsExactlyInAnyOrderElementsOf(expected);
    }

    @Test
    void v2KeepsEmailUniqueConstraintAndAddsIndexes() throws IOException {
        String v1 = readResource(V1);
        String v2 = readResource(V2);

        assertThat(v1).contains("CONSTRAINT uk_customers_email UNIQUE (email)");
        assertThat(v2).doesNotContain("DROP COLUMN email").doesNotContain("uk_customers_email");
        assertThat(v2).contains("CREATE INDEX idx_customers_last_name_first_name");
        assertThat(v2).contains("CREATE INDEX idx_customers_status");
    }

    // ---- helpers ----------------------------------------------------------

    private static Column columnAnnotation(String columnName) {
        for (Field field : Customer.class.getDeclaredFields()) {
            Column column = field.getAnnotation(Column.class);
            if (column != null && column.name().equals(columnName)) {
                return column;
            }
        }
        throw new AssertionError("No entity field maps column " + columnName);
    }

    private static String readResource(String path) throws IOException {
        try (InputStream in = CustomerMappingTest.class.getClassLoader().getResourceAsStream(path)) {
            assertThat(in).as("resource on classpath: " + path).isNotNull();
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    private static Map<String, SqlColumn> readV1Columns() throws IOException {
        Pattern line = Pattern.compile(
                "^\\s*([a-z_]+)\\s+(BIGINT|VARCHAR|TIMESTAMP)(?:\\((\\d+)\\))?(.*)$", Pattern.CASE_INSENSITIVE);
        Map<String, SqlColumn> columns = new HashMap<>();
        for (String text : readResource(V1).split("\\R")) {
            Matcher matcher = line.matcher(text);
            if (matcher.matches()) {
                Integer length = matcher.group(3) == null ? null : Integer.valueOf(matcher.group(3));
                boolean notNull = matcher.group(4).toUpperCase().contains("NOT NULL");
                columns.put(matcher.group(1), new SqlColumn(length, notNull));
            }
        }
        return columns;
    }
}
