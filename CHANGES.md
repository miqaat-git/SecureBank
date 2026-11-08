# Change list

Created
* `docs/AUTHENTICATION.md` - audit result, role matrix, env table, PowerShell runbook, checklist, open defects
* `identity/keycloak/securebank-realm.json`, `identity/README.md`
* `identity/scripts/install-keycloak.ps1`, `start-keycloak.ps1`, `get-token.ps1`, `set-keycloak-env.example.ps1`
* `scripts/set-env.example.ps1` - environment template for all services
* `.gitignore` (root)
* Ledger: `config/MissingEnvironmentVariableFailureAnalyzer.java`, `META-INF/spring.factories`,
  `security/JwtIssuerStartupValidator.java`
* Ledger tests: `config/MissingEnvironmentVariableFailureAnalyzerTest`, `security/JwtIssuerStartupValidatorTest`,
  `security/JwtValidationContractTest`

Modified
* `ledger-service/README.md` - issuer variable, 127.0.0.1, startup behaviour

Unchanged (deliberately): API Gateway, Customer Service, Account Service sources and configuration, security
rules, role names, `application.yml` of Ledger (the issuer stays mandatory, no default), Transaction Service.
Removed: nothing.
