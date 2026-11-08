# Phase 0 compatibility record (check 6)

How to read this file: every statement is tagged. **[official]** means it was read on an official
Spring page during Phase 0. **[third-party]** means it comes from a non-Spring source.
**[not verified]** means it could not be checked in the authoring environment and must be
confirmed by running the command shown. Nothing here was produced by building the project.

## Pinned versions

| Component | Version | How it is pinned | Status |
|---|---|---|---|
| Java | 21 (`java.version` in the POM) | POM property; authoring JDK was 21.0.12 | Boot 3.5.16 supports Java 17 up to 25 **[official]** |
| Spring Boot | **3.5.16** | exact version in `<parent>` | exists and is the last open-source 3.5.x release, released 25 Jun 2026 **[official: spring.io blog "Spring Boot 3.5.16 available now"]** |
| Spring Framework | managed by Boot (6.2.x; Boot 3.5.16 requires 6.2.19 or later) | Boot BOM | requirement **[official]**; resolved value **[not verified]** |
| Spring Security | managed by Boot (6.5.x expected) | Boot BOM | property `spring-security.version` exists **[official]**; value **[not verified]** |
| Embedded Tomcat | managed by Boot (10.1.x; Boot 3.5 needs 10.1.25 or later, Servlet 6.0) | Boot BOM | requirement **[official]**; value **[not verified]** |
| Standalone Tomcat | 10.1.25 or later | operator choice (docs/deploy-tomcat.md) | minimum from Boot requirement **[official]**; no Tomcat was available to test |
| Jakarta Servlet API | 6.0.0 | Boot BOM | **[official]** |
| JSP | 3.1 (Jasper from Tomcat 10.1) | `tomcat-embed-jasper`, Boot BOM | value **[not verified]** |
| Jakarta JSTL API | 3.0.2 | Boot BOM | **[official]** |
| Jakarta JSTL implementation | `org.glassfish.web:jakarta.servlet.jsp.jstl`, managed by `glassfish-jstl.version` | Boot BOM | property exists **[official]**; value **[not verified]** |
| Hibernate | managed by Boot | Boot BOM | property `hibernate.version` exists **[official]**; value **[not verified]** |
| Flyway (core, MySQL module, Maven plugin) | managed by `flyway.version` | Boot BOM; the plugin version is `${flyway.version}` | property exists **[official]**; value **[not verified]** |
| MySQL Connector/J | 9.7.0 | Boot BOM | **[official]** |
| MySQL server | 8.x (tests use `mysql:8.0`) | Testcontainers image tag | not run |
| Testcontainers | managed by `testcontainers.version` (modules `junit-jupiter`, `mysql`) | Boot BOM | property exists **[official]**; value and module names **[not verified]** |
| JUnit Jupiter, Mockito | managed by Boot | Boot BOM | properties exist **[official]**; values **[not verified]** |
| Maven | 3.6.3 or later required by Boot 3.5.16 **[official]**; authoring environment had 3.9.11 | n/a | build never ran |

To print every resolved version, run `mvn help:effective-pom` and `mvn dependency:tree`
(**not run** in the authoring environment, see the report).

## Support status of Spring Boot 3.5 (decision D1)

- 3.5.16 is the final open-source 3.5.x release. **[official]**
- Open-source support for 3.5 ended on 30 June 2026. **[third-party: HeroDevs]** The Spring blog
  post names 3.5.16 as the last open-source release but gives no date.
- Paid extended support exists. **[third-party: HeroDevs]**
- Consequence (D1): there are no free security patches for Boot 3.5 or its managed
  dependencies, so SecureBank runs only on a private or local server with fictional data.

## Why the JSTL taglib URI is `jakarta.tags.core`

The proof page uses `<%@ taglib prefix="c" uri="jakarta.tags.core" %>`, the URI of Jakarta
Standard Tag Library 3.0 (the Jakarta EE 10 namespace). **[not verified]** against the packaged
TLD; if the page fails with an unresolvable taglib URI, inspect the JSTL implementation jar's
`META-INF/*.tld` and correct the URI. This is a directive, not a scriptlet.

## Dependency hygiene to confirm (check 1, not run)

Expected: no `javax.servlet`, `javax.servlet.jsp` or `javax.servlet.jsp.jstl` artifacts, and no
second JSTL implementation. Tomcat's own `tomcat-embed-*` jars are `provided`. If Maven reports a
transitive `javax.*` artifact (for example `javax.annotation` or `javax.transaction` APIs from an
older library), record why it is unavoidable instead of banning the name. Command:
`mvn dependency:tree -Dincludes=javax.servlet,jakarta.servlet,org.glassfish.web,org.apache.tomcat.embed`.
