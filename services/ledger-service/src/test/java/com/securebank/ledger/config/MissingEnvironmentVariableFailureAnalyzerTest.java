package com.securebank.ledger.config;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.BeanCreationException;
import org.springframework.boot.diagnostics.FailureAnalysis;

class MissingEnvironmentVariableFailureAnalyzerTest {

    private final MissingEnvironmentVariableFailureAnalyzer analyzer = new MissingEnvironmentVariableFailureAnalyzer();

    @Test
    void namesTheMissingIssuerVariable() {
        Throwable failure = new BeanCreationException("x", new IllegalArgumentException(
                "Could not resolve placeholder 'LEDGER_JWT_ISSUER_URI' in value \"${LEDGER_JWT_ISSUER_URI}\""));
        FailureAnalysis analysis = analyzer.analyze(failure);
        assertThat(analysis).isNotNull();
        assertThat(analysis.getDescription()).contains("LEDGER_JWT_ISSUER_URI");
        assertThat(analysis.getAction()).contains("$env:LEDGER_JWT_ISSUER_URI").contains("8180/realms/securebank");
    }

    @Test
    void namesAMissingDatabaseVariable() {
        FailureAnalysis analysis = analyzer.analyze(new IllegalArgumentException(
                "Could not resolve placeholder 'LEDGER_DB_URL' in value \"${LEDGER_DB_URL}\""));
        assertThat(analysis).isNotNull();
        assertThat(analysis.getDescription()).contains("LEDGER_DB_URL");
    }

    @Test
    void ignoresOtherFailures() {
        assertThat(analyzer.analyze(new IllegalArgumentException("something else"))).isNull();
        assertThat(analyzer.analyze(new IllegalArgumentException(
                "Could not resolve placeholder 'OTHER_VAR' in value \"${OTHER_VAR}\""))).isNull();
    }
}
