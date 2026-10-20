package com.securebank.ledger.support;

import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

/**
 * Base for integration tests: full Spring context on in-memory H2 (never the real MySQL), real Flyway
 * migration, real Hibernate schema validation. Tables are emptied through JDBC before each test (the
 * application itself can never delete ledger rows).
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
public abstract class AbstractLedgerTest {

    @Autowired
    protected JdbcTemplate jdbc;

    @BeforeEach
    protected void cleanDatabase() {
        jdbc.update("delete from journal_lines");
        jdbc.update("delete from journal_entries where reverses_entry_id is not null");
        jdbc.update("delete from journal_entries");
    }

    protected long countEntries() {
        Long count = jdbc.queryForObject("select count(*) from journal_entries", Long.class);
        return count == null ? 0 : count;
    }

    protected long countLines() {
        Long count = jdbc.queryForObject("select count(*) from journal_lines", Long.class);
        return count == null ? 0 : count;
    }
}
