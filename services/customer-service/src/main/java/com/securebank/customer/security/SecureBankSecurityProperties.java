package com.securebank.customer.security;

import org.springframework.boot.context.properties.ConfigurationProperties;

/** Settings under "securebank.security.*". */
@ConfigurationProperties(prefix = "securebank.security")
public class SecureBankSecurityProperties {

    private final Jwt jwt = new Jwt();

    /** Serve the OpenAPI document and Swagger UI without authentication. Keep false in production. */
    private boolean publicApiDocs = false;

    public Jwt getJwt() {
        return jwt;
    }

    public boolean isPublicApiDocs() {
        return publicApiDocs;
    }

    public void setPublicApiDocs(boolean publicApiDocs) {
        this.publicApiDocs = publicApiDocs;
    }

    public static class Jwt {

        /** Issuer of accepted tokens (the future Keycloak realm URL). Empty = every token is rejected. */
        private String issuerUri = "";

        /** Keycloak realm role that grants ROLE_ADMIN. */
        private String adminRole = "admin";

        /** Keycloak realm role that grants ROLE_STAFF. */
        private String staffRole = "admin";

        /** Keycloak realm role of the Account Service's service account; grants ROLE_ACCOUNT_SERVICE. */
        private String accountServiceRole = "account-service";

        public String getAccountServiceRole() {
            return accountServiceRole;
        }

        public void setAccountServiceRole(String accountServiceRole) {
            this.accountServiceRole = accountServiceRole;
        }

        public String getIssuerUri() {
            return issuerUri;
        }

        public void setIssuerUri(String issuerUri) {
            this.issuerUri = issuerUri;
        }

        public String getAdminRole() {
            return adminRole;
        }

        public void setAdminRole(String adminRole) {
            this.adminRole = adminRole;
        }

        public String getStaffRole() {
            return staffRole;
        }

        public void setStaffRole(String staffRole) {
            this.staffRole = staffRole;
        }
    }
}
