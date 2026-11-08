package com.securebank.ledger.config;

import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.boot.diagnostics.AbstractFailureAnalyzer;
import org.springframework.boot.diagnostics.FailureAnalysis;

/**
 * Turns Spring's "Could not resolve placeholder 'LEDGER_...'" startup failure into a readable message that
 * names the missing variable and says where its value comes from. It never prints any configuration value.
 */
public class MissingEnvironmentVariableFailureAnalyzer extends AbstractFailureAnalyzer<IllegalArgumentException> {

    private static final Pattern PLACEHOLDER = Pattern.compile("Could not resolve placeholder '(LEDGER_[A-Z0-9_]+)'");

    @Override
    protected FailureAnalysis analyze(Throwable rootFailure, IllegalArgumentException cause) {
        Matcher matcher = PLACEHOLDER.matcher(String.valueOf(cause.getMessage()));
        if (!matcher.find()) {
            return null;
        }
        String name = matcher.group(1);
        String description = "Required environment variable " + name + " is not set.";
        String action = "Set " + name + " before starting Ledger Service (PowerShell, current session only: "
                + "$env:" + name + " = \"<value>\"). The easiest way is to copy scripts\\set-env.example.ps1 "
                + "(in the SecureBank root folder) to scripts\\set-env.local.ps1, fill in your values and run it "
                + "with a leading dot: . <root>\\scripts\\set-env.local.ps1";
        if ("LEDGER_JWT_ISSUER_URI".equals(name)) {
            action += System.lineSeparator() + "LEDGER_JWT_ISSUER_URI must be exactly the 'iss' value of the access "
                    + "tokens your identity provider issues. For the local Keycloak in the identity folder it is "
                    + "http://localhost:8180/realms/securebank (start Keycloak first; see identity\\README.md).";
        }
        return new FailureAnalysis(description, action, cause);
    }
}
