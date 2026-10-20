# Deploying the WAR to Apache Tomcat 10.1

Use a clean Tomcat 10.1 installation, release 10.1.25 or later (the minimum Spring Boot 3.5
documents). Tomcat 9 and earlier use the `javax.*` namespace and will not work.

## Build

```bash
mvn clean package
ls -l target/securebank.war
scripts/verify-war.sh
```

## Phase 0 proof deployment (no database)

The proof page exists only under the dev or test profile. Because the application is
production-safe when no profile is set, select the profile for this check explicitly:

```bash
export CATALINA_OPTS="-Dspring.profiles.active=dev,nodb"
cp target/securebank.war "$CATALINA_HOME/webapps/"
"$CATALINA_HOME/bin/startup.sh"
curl -i http://localhost:8080/securebank/phase0/proof
```

Expected: HTTP 200 and an HTML body containing `EL arithmetic: 3`, the `<li>` items
(`alpha`, `beta`, `gamma`, and the escaped `a&amp;b &lt;i&gt;tag&lt;/i&gt;`) and a
`Servlet container: Apache Tomcat/10.1.x` line. Also confirm that
`curl -i http://localhost:8080/securebank/WEB-INF/views/phase0/proof.jsp` does **not** return the page.

Only a dev, local-machine deployment should use this profile.

## Production-style deployment (outline; completed in Phase 8)

- Serve over HTTPS, terminating TLS at Tomcat or at a reverse proxy; behind a proxy, configure
  Tomcat to trust the forwarded headers so the application sees HTTPS (blueprint 5.1).
- Do not set a profile (or set `prod`): the session cookie is then Secure.
- Provide `SB_DB_URL`, `SB_APP_DB_USER` and `SB_APP_DB_PASSWORD` to the Tomcat process.
  Do **not** provide any migration variable to Tomcat.
- Apply migrations beforehand with `scripts/migrate.sh` (docs/database-setup.md).
- Keep this deployment private: Spring Boot 3.5 no longer receives free security patches.
