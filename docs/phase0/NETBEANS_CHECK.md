# NetBeans compatibility check (Phase 0, check 7: manual-only)

**Status: NOT RUN.** The environment that authored this project has no NetBeans and no display,
so nothing below has been observed. Run it on your machine and record the result at the bottom.

## Why it should work (design intent, not evidence)

- The project is a standard Maven WAR project: `pom.xml` at the root, sources in
  `src/main/java`, `src/main/resources`, `src/main/webapp`, tests in `src/test`.
- No IDE metadata is committed, and `.gitignore` excludes it.
- The command-line Maven build is the reference build; the IDE only needs to invoke Maven.

## Steps

1. Use a NetBeans release that supports Java 21, Maven projects and Apache Tomcat 10.1 (check
   its release notes before starting; this has not been verified).
2. File > Open Project, choose the repository root (the folder with `pom.xml`). Expect NetBeans
   to recognise a Maven "Web Application" project.
3. Confirm the project JDK is 21 (Project Properties > Sources and Build).
4. Run *Clean and Build*. Expect `target/securebank.war`, the same artefact as
   `mvn clean package` on the command line.
5. Register an Apache Tomcat 10.1 server (Services > Servers > Add Server).
6. In Project Properties > Run, select that server. Add the VM option
   `-Dspring.profiles.active=dev,nodb` to the server's JVM options for this check.
7. Run the project and open `http://localhost:8080/securebank/phase0/proof`. Expect the rendered
   proof page.
8. Compare `target/securebank.war` from NetBeans with the command-line build (same file names
   inside, for example with `unzip -Z1`).

## Result (fill in)

| Item | Result | NetBeans and JDK versions |
|---|---|---|
| Project opens as a Maven web project | | |
| Clean and Build produces the WAR | | |
| Deploys to Tomcat 10.1 | | |
| Proof page renders | | |
| WAR matches the command-line build | | |
