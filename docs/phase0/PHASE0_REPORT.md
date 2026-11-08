# SecureBank Phase 0 report

**Overall result: Phase 0 is implemented but NOT verified.** The authoring environment could not
download any Maven dependency (the egress policy refuses `repo.maven.apache.org`,
`repo1.maven.org`, `dlcdn.apache.org`, `archive.apache.org`, `docs.spring.io` for the shell and
others), has no Docker daemon, no MySQL, no Tomcat and no package manager access. The project
was therefore **never compiled, built, started or tested**. No check below is reported as
passed unless it says so, and none of the seven mandatory checks passed.

## 1. Files created or changed

Project (`securebank/`):

- `pom.xml`, `.gitignore`, `README.md`
- `src/main/java/com/securebank/SecureBankApplication.java`
- `src/main/java/com/securebank/config/SecurityConfig.java`
- `src/main/java/com/securebank/web/Phase0ProofController.java`
- `src/main/webapp/WEB-INF/views/phase0/proof.jsp`
- `src/main/resources/application.properties`, `application-dev.properties`,
  `application-prod.properties`, `application-nodb.properties`, `db/migration/.gitkeep`
- `src/test/java/com/securebank/JspRenderingHttpTest.java` (5 tests)
- `src/test/java/com/securebank/ProfileConfigurationTest.java` (4 tests)
- `src/test/java/com/securebank/DatabaseAccountSeparationTest.java` (5 tests)
- `src/test/resources/application-test.properties`, `db/phase0-proof/V0_1__phase0_probe.sql`
- `db/setup/accounts.sql.template`, `db/setup/create-db-accounts.sh`
- `scripts/migrate.sh`, `scripts/verify-war.sh`
- `docs/database-setup.md`, `docs/deploy-tomcat.md`, `docs/upgrade-path-boot4-tomcat11.md`,
  `docs/phase0/COMPATIBILITY.md`, `docs/phase0/NETBEANS_CHECK.md`, `docs/phase0/PHASE0_REPORT.md`
- `docs/blueprint/SecureBank_Blueprint_v2.3.txt` (copy of the blueprint)

Nothing from the reference project was copied. No Phase 1 or later feature was implemented: no
business schema, entities, accounts, transfers, ledger, funding, dashboard, admin console or
real authentication.

## 2. Pinned versions and official evidence

See `COMPATIBILITY.md` for every row with its evidence tag. In short:

- **Pinned exactly:** Spring Boot 3.5.16 (parent). Java 21. Everything else is pinned through the
  Boot 3.5.16 BOM.
- **Read on official Spring pages:** 3.5.16 exists, was released on 25 Jun 2026 and is the last
  open-source 3.5.x release; Boot 3.5.16 supports Java 17 to 25, needs Spring Framework 6.2.19 or
  later, Maven 3.6.3 or later, and embeds Tomcat 10.1 (10.1.25 or later, Servlet 6.0); the BOM
  manages mysql-connector-j 9.7.0, jakarta.servlet.jsp.jstl-api 3.0.2 and jakarta.servlet-api
  6.0.0, and defines version properties for Tomcat, Spring Security, Spring Framework, Hibernate,
  Flyway, both JSTL artifacts, Testcontainers, Mockito and JUnit Jupiter. Boot 4.0.8 needs Java 17
  or later, Spring Framework 7.0.9 or later and Tomcat 11.0.x with Servlet 6.1.
- **Third-party only:** the 30 Jun 2026 end of open-source support for 3.5 (HeroDevs).
- **Not verified:** the resolved values of Tomcat, Spring Security, Hibernate, Flyway,
  Testcontainers, the Glassfish JSTL implementation, Mockito and JUnit (the official coordinates
  page was truncated and Maven Central was unreachable). Resolve them with
  `mvn help:effective-pom`.

## 3. Commands actually executed (authoring environment)

```
java -version; mvn -v; which docker mysql mysqld mariadb; ls /opt
curl https://repo.maven.apache.org/..., dlcdn.apache.org, archive.apache.org   -> proxy 403
curl <17 other hosts>                                                          -> all refused except github.com (400, unused)
docker info                                  -> client only; no daemon (/var/run/docker.sock missing)
apt-get update                               -> hosts cannot be resolved
python3 -I -c "xml.dom.minidom.parse('pom.xml')"                -> well-formed
bash -n db/setup/create-db-accounts.sh scripts/migrate.sh scripts/verify-war.sh  -> all parse
javac -proc:none -d <scratch> $(find src -name '*.java')   -> see section 4
PATH=<stub mysql> db/setup/create-db-accounts.sh (dry run with a stub mysql)    -> see section 7
pip install sqlglot                                          -> not available
```

Environment found: OpenJDK 21.0.12.1, Apache Maven 3.9.11, Ubuntu 24.04.

## 4. Test results

| Suite | Tests written | Run | Passed | Failed | Skipped |
|---|---|---|---|---|---|
| JspRenderingHttpTest | 5 | 0 | 0 | 0 | 0 |
| ProfileConfigurationTest | 4 | 0 | 0 | 0 | 0 |
| DatabaseAccountSeparationTest | 5 | 0 | 0 | 0 | 0 |
| **Total** | **14** | **0** | **0** | **0** | **0** |

`mvn` could not resolve dependencies, so no test ran. The only code-level evidence is a javac
parse: it reported **no syntax errors**, and only unresolved-symbol and missing-package errors
(for example 55 "cannot find symbol"), which is expected because no library was on the
classpath. That is not a compilation and proves nothing about the code's correctness.

## 5. Embedded JSP HTTP verification (check 2 and check 5)

- Check 2 (`mvn spring-boot:run` plus a real HTTP request): **NOT RUN.**
- Check 5 (automated real-HTTP test, `JspRenderingHttpTest`): **written, NOT RUN.** It uses
  `java.net.http` against embedded Tomcat on a random port, so the JSP engine executes; it
  asserts EL output, JSTL `<li>` items, `c:out` escaping, no raw `${`, direct access to
  `WEB-INF` not served, POST without CSRF rejected (403), default security headers present.

## 6. External Tomcat deployment (check 3)

**NOT RUN.** No Tomcat 10.1 was available and downloads from Apache hosts are blocked. The
procedure is in `docs/deploy-tomcat.md`.

## 7. Database privilege and migration verification (check 4)

- Real MySQL privilege and Flyway checks (`DatabaseAccountSeparationTest`): **written, NOT RUN**
  (no MySQL, no Docker daemon).
- What was observed: a dry run of `db/setup/create-db-accounts.sh` against a **stub** `mysql`
  confirmed that it substitutes the template correctly, passes the administrator password only
  through `MYSQL_PWD` (not on the command line), sends the SQL on standard input, and **refuses**
  a short password and a schema name containing `_`. This does not test MySQL behavior.
- Parsing the SQL with `sqlglot`: **NOT RUN** (package unavailable).
- Keeping migration credentials out of the runtime: designed (separate plugin credentials from
  the environment only, plugin not bound to a lifecycle phase, no Flyway jars or `pom.xml` in the
  WAR) and covered by `ProfileConfigurationTest` and `scripts/verify-war.sh`; **neither has run**.

## 8. NetBeans (check 7)

**NOT RUN (manual-only).** Checklist and an empty result table: `docs/phase0/NETBEANS_CHECK.md`.
The command-line Maven build that is meant to be the reference build has also **not been run**.

## 9. Remaining risks, blockers and unverified items

Blockers: everything above must be run on a machine with Maven Central access, Docker or a local
MySQL 8, and Tomcat 10.1.

Things most likely to need a small fix when first built (all unverified):

1. JSTL taglib URI `jakarta.tags.core` against the packaged JSTL implementation.
2. Testcontainers module names: the POM uses `org.testcontainers:mysql` and `junit-jupiter`; if the
   Boot 3.5.16 BOM manages Testcontainers 2.x, the MySQL module is named differently.
3. `dispatcherTypeMatchers(DispatcherType.FORWARD, DispatcherType.ERROR)` in `SecurityConfig` and
   the empty `InMemoryUserDetailsManager` (intended to prevent a generated password being logged).
4. JSP rendering needs Jasper on the classpath: it is `provided`, which `spring-boot:run`, the
   tests and the executable WAR include; confirm the first time.
5. `flyway-maven-plugin` credentials from `${env.*}`; plugin dependency versions use `${flyway.version}`
   and `${mysql.version}` from the Boot parent.
6. MySQL: creating triggers later may need extra server settings when binary logging is on.
7. The Boot 4 and Tomcat 11 plan is partly from general knowledge (marked in the document).

Deviations and open items for you:

- **N3** (table-level grant timing) and **N4** (proof page lifecycle) are new open items in
  blueprint v2.3; neither blocks Phase 0.
- Blueprint 2.3 check 4 allows Flyway at startup in dev and test. Phase 0 does not use that: it
  runs migrations only through `scripts/migrate.sh`, in every profile.
- The proof page is served only under the dev and test profiles. An external-Tomcat check therefore
  needs `-Dspring.profiles.active=dev,nodb`.

## 10. How to run it locally

See the README. In short: `mvn clean package`, then
`mvn spring-boot:run -Dspring-boot.run.profiles=dev,nodb` and
`curl -i http://localhost:8080/phase0/proof`.

## 11. Blueprint v2.3 and the resolution of N1, N2 and I8

N1, N2 and I8 are resolved in `docs/blueprint/SecureBank_Blueprint_v2.3.txt`. Sections changed:
header and status; 0.2 (I8 row rewritten, N1 and N2 rows added); 2.3 (proof page note); 5 (brute
force row); 5.2 (I8 enforcement wording, new throttle rules); 7 (Profile and Password rows);
8 (three new test groups); 9 (Phase 2 and Phase 3 rows, navigation bullet, dependency notes);
11 (one new risk); 13 (rewritten, N3 and N4 added); 14; 15 (I8 updated, N1 and N2 added). Nothing
else was changed.

## 12. Recommendation

Do not treat Phase 0 as complete. Run the checks above on a machine with network access first
(about an hour of work), then report the results. Proceed to Phase 1 only after that report is
reviewed and N3 is settled.
