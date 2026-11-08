package com.securebank.transaction;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

/**
 * Blueprint D5/D7: v1 has no settlement provider, clearing account, gateway or callback. The funding workflow must
 * not need any provider bean or configuration, and no settlement schema may exist.
 */
class NoSettlementDependencyTest {

    private static final Path MAIN = Path.of("src/main");

    @Test
    void noSettlementOrClearingCodeSchemaOrConfigurationExistsInMain() throws IOException {
        try (Stream<Path> files = Files.walk(MAIN)) {
            files.filter(Files::isRegularFile).forEach(f -> {
                String name = f.getFileName().toString().toLowerCase();
                assertThat(name).as(f.toString()).doesNotContain("settlement").doesNotContain("clearing");
                try {
                    String text = Files.readString(f).toLowerCase();
                    // comments that explain the exclusion are allowed only in the funding classes that say "no"
                    if (!text.contains("no settlement provider") && !text.contains("no external source")) {
                        assertThat(text).as(f.toString()).doesNotContain("settlementprovider")
                                .doesNotContain("settlement_event").doesNotContain("funding_settlement")
                                .doesNotContain("clearing-account").doesNotContain("allow-without-settlement");
                    }
                } catch (IOException e) {
                    throw new IllegalStateException(e);
                }
            });
        }
    }

    @Test
    void applicationYamlRequiresNoProviderConfiguration() throws IOException {
        String yml = Files.readString(MAIN.resolve("resources/application.yml"));
        assertThat(yml).contains("max-amount").doesNotContain("FUNDING_SETTLEMENT").doesNotContain("FUNDING_CLEARING");
    }
}
