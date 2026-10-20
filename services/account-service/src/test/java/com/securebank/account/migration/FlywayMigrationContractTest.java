package com.securebank.account.migration;

import static org.assertj.core.api.Assertions.assertThat;

import com.securebank.account.domain.Account;
import com.securebank.account.domain.BalanceOperation;
import jakarta.persistence.Column;
import jakarta.persistence.Table;
import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Properties;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

/**
 * Static contract between the Flyway V1 script and the JPA mappings. It cannot replace running Flyway against
 * MySQL (see README, Known limitations) but it catches the common drift: a mapped column or table missing from
 * the migration, a missing unique constraint, wrong money type, or an unsafe ddl-auto setting.
 */
class FlywayMigrationContractTest {

    private static String read(String resource) throws IOException {
        try (InputStream in = FlywayMigrationContractTest.class.getClassLoader().getResourceAsStream(resource)) {
            assertThat(in).as("resource " + resource).isNotNull();
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    private static String sql() throws IOException {
        return read("db/migration/V1__create_account_schema.sql").toLowerCase(Locale.ROOT);
    }

    /** Returns the text of the CREATE TABLE statement for the given table. */
    private static String createTable(String sql, String table) {
        Matcher matcher = Pattern.compile("create table " + table + " \\((.*?)\\) engine=innodb", Pattern.DOTALL)
                .matcher(sql);
        assertThat(matcher.find()).as("CREATE TABLE " + table).isTrue();
        return matcher.group(1);
    }

    private static List<String> mappedColumns(Class<?> entity) {
        List<String> columns = new ArrayList<>();
        for (Field field : entity.getDeclaredFields()) {
            Column column = field.getAnnotation(Column.class);
            if (column != null) {
                columns.add(column.name().toLowerCase(Locale.ROOT));
            }
        }
        return columns;
    }

    private void assertEveryMappedColumnExists(Class<?> entity) throws IOException {
        String table = entity.getAnnotation(Table.class).name();
        String ddl = createTable(sql(), table);
        List<String> columns = mappedColumns(entity);
        assertThat(columns).isNotEmpty();
        for (String column : columns) {
            assertThat(Pattern.compile("(^|\\s)" + column + "\\s+(binary|varchar|decimal|datetime|bigint)")
                    .matcher(ddl).find())
                    .as("column %s.%s must be defined in V1 migration", table, column).isTrue();
        }
    }

    @Test
    void everyColumnMappedOnAccountExistsInTheMigration() throws IOException {
        assertEveryMappedColumnExists(Account.class);
    }

    @Test
    void everyColumnMappedOnBalanceOperationExistsInTheMigration() throws IOException {
        assertEveryMappedColumnExists(BalanceOperation.class);
    }

    @Test
    void migrationDefinesUniquenessAndSafetyConstraints() throws IOException {
        String sql = sql();
        assertThat(sql).contains("constraint uq_accounts_account_number unique (account_number)");
        assertThat(sql).contains("constraint uq_balance_operations_idempotency_key unique (idempotency_key)");
        assertThat(sql).contains("ck_accounts_balance_non_negative check (available_balance >= 0)");
        assertThat(sql).contains("foreign key (account_id) references accounts (id)");
        assertThat(sql).contains("idx_accounts_customer_id_created");
        assertThat(sql).contains("idx_accounts_status");
    }

    @Test
    void moneyColumnsUseDecimalNineteenTwo() throws IOException {
        String ddl = sql();
        for (String column : List.of("available_balance", "amount", "resulting_balance")) {
            assertThat(Pattern.compile(column + "\\s+decimal\\(19,2\\)").matcher(ddl).find())
                    .as("%s must be DECIMAL(19,2)", column).isTrue();
        }
        assertThat(ddl).doesNotContain("float").doesNotContain("double");
    }

    @Test
    void migrationHasNoForeignKeyIntoTheCustomerDatabase() throws IOException {
        String ddl = createTable(sql(), "accounts");
        assertThat(ddl).doesNotContain("references");
    }

    @Test
    void migrationNeverDropsOrTruncatesData() throws IOException {
        String sql = sql();
        assertThat(sql).doesNotContain("drop table").doesNotContain("drop database").doesNotContain("truncate");
    }

    @Test
    void productionConfigurationUsesHibernateValidateAndPort8082() throws IOException {
        Properties properties = new Properties();
        properties.load(new java.io.StringReader(read("application.properties")));
        assertThat(properties.getProperty("spring.jpa.hibernate.ddl-auto")).isEqualTo("validate");
        assertThat(properties.getProperty("server.port")).isEqualTo("8082");
        assertThat(properties.getProperty("spring.flyway.enabled")).isEqualTo("true");
        assertThat(properties.getProperty("spring.flyway.user")).contains("ACCOUNT_DB_MIGRATION_USERNAME");
        assertThat(properties.getProperty("spring.datasource.username")).contains("ACCOUNT_DB_USERNAME");
    }
}
