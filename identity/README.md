# SecureBank identity provider (Keycloak, local development)

SecureBank has no token issuer of its own; all four services validate tokens from one Keycloak realm.
This folder holds the pinned, reproducible local setup. Full runbook and startup order: `..\docs\AUTHENTICATION.md`.

* `keycloak/securebank-realm.json` - realm `securebank`: the nine realm roles the services expect, users
  `dev-admin`, `dev-staff`, `dev-customer` (with a `customer_id` claim), clients `securebank-web` (code + PKCE),
  `securebank-transaction-service` (client credentials, roles `transaction-service`, `ledger-writer`,
  `ledger-reverser`) and `securebank-dev-cli` (password grant, **dev only**). Passwords and the client secret are
  `${ENV}` placeholders filled from your environment at import time; nothing secret is committed.
* `scripts/install-keycloak.ps1`, `start-keycloak.ps1`, `get-token.ps1`, `set-keycloak-env.example.ps1`.

Issuer: `http://localhost:8180/realms/securebank` (fixed by `--hostname`, so it is the same whether you browse
to `localhost` or `127.0.0.1`).

| User | Realm roles |
|---|---|
| dev-admin | admin, securebank-admin, securebank-staff, bank-admin, ledger-reader |
| dev-staff | securebank-staff, ledger-reader |
| dev-customer | customer (+ `customer_id` claim) |
| service account of `securebank-transaction-service` | transaction-service, ledger-writer, ledger-reverser |

The realm is imported only when it does not exist yet. To start over: stop Keycloak, delete
`.keycloak\keycloak-26.4.0\data`, start again.
