\# SecureBank Upgrade Path: Spring Boot 4 and Tomcat 11



\*\*Status:\*\* Planned; migration not yet performed.



\*\*Current baseline:\*\* Java 21, Spring Boot 3.5.16, Spring Framework 6.2.x, Spring Security 6.5.x, WAR packaging, external Tomcat 10.1, JSP/JSTL, and MySQL 8.



\## 1. Target Platform



The intended upgrade target is a supported Spring Boot 4.x release with Spring Framework 7.x and an external Apache Tomcat 11.0.x container.



Spring Boot 4 uses Jakarta EE 11 and requires a Servlet 6.1-compatible container. Java 21 meets its minimum Java requirement, but all dependencies must be checked for compatibility before migration.



\## 2. Expected Changes



1\. \*\*Dependencies:\*\* Update the Spring Boot parent/BOM and review Spring Framework, Spring Security, Spring Data, Hibernate, testing libraries, MySQL Connector/J, and JSP/JSTL dependencies.

2\. \*\*Servlet container:\*\* Move from Tomcat 10.1 to Tomcat 11.0 and review Servlet 6.1, Jakarta Pages, and Expression Language changes.

3\. \*\*WAR packaging:\*\* Review the Spring Boot 4 migration guide for external WAR deployment and the required Tomcat runtime dependency.

4\. \*\*Application code:\*\* Check for removed APIs, changed packages, deprecated methods, and framework behavior changes.

5\. \*\*Configuration:\*\* Review application properties and Spring configuration for renamed or removed properties.

6\. \*\*JSP/JSTL:\*\* Re-run HTTP rendering tests, including EL evaluation, JSTL iteration, HTML escaping, and the simulator disclaimer.

7\. \*\*Tomcat configuration:\*\* Start with a clean Tomcat 11 configuration and reapply required settings.



\## 3. Migration Procedure



1\. Select a supported Spring Boot 4.x release and compatible dependencies using official documentation.

2\. Create a separate migration branch or backup.

3\. Update the Maven configuration and dependency versions.

4\. Check the dependency tree for incompatible `javax.\*` artifacts.

5\. Run the complete automated test suite and package the WAR.

6\. Deploy the WAR to a clean standalone Tomcat 11 instance.

7\. Verify JSP rendering, authentication, session and cookie settings, database connectivity, Flyway account separation, and security tests.

8\. Compare the results with the existing Boot 3/Tomcat 10.1 baseline.



\## 4. Risks and Acceptance Criteria



A successful Maven build alone does not mean the migration is complete. Acceptance requires passing automated tests and verifying that the application works on the target Tomcat version.



Do not upgrade the current Phase 0 baseline during the compatibility spike. Complete and document the current baseline checks first, then perform the migration as a separately tested change.



\## 5. Official References



\- Spring Boot 4 migration guide: https://github.com/spring-projects/spring-boot/wiki/Spring-Boot-4.0-Migration-Guide

\- Spring Boot 4 system requirements: https://docs.spring.io/spring-boot/4.0/system-requirements.html

\- Apache Tomcat 11 migration guide: https://tomcat.apache.org/migration-11.0.html

