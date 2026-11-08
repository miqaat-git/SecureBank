package com.securebank.account.security;

import com.securebank.account.config.AccountProperties;
import com.securebank.account.exception.ApiException;
import com.securebank.account.exception.ErrorCode;
import java.util.UUID;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.stereotype.Component;

/** Derives the {@link CallerContext} from the current Spring Security authentication. */
@Component
public class CallerResolver {

    static final String ROLE_ADMIN = "ROLE_ADMIN";
    static final String ROLE_CUSTOMER = "ROLE_CUSTOMER";

    private final String customerIdClaim;

    public CallerResolver(AccountProperties properties) {
        this.customerIdClaim = properties.security().customerIdClaim();
    }

    public CallerContext current() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null || !authentication.isAuthenticated()) {
            throw new ApiException(ErrorCode.AUTHENTICATION_REQUIRED, "Authentication is required.");
        }
        boolean admin = hasAuthority(authentication, ROLE_ADMIN);
        UUID customerId = null;
        if (!admin && hasAuthority(authentication, ROLE_CUSTOMER)
                && authentication instanceof JwtAuthenticationToken jwtAuthentication) {
            customerId = parseUuid(jwtAuthentication.getToken().getClaimAsString(customerIdClaim));
        }
        return new CallerContext(authentication.getName(), admin, customerId);
    }

    /** Name of the authenticated principal (JWT subject); used as actor for internal operations. */
    public String currentSubject() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null || !authentication.isAuthenticated()) {
            throw new ApiException(ErrorCode.AUTHENTICATION_REQUIRED, "Authentication is required.");
        }
        return authentication.getName();
    }

    private static boolean hasAuthority(Authentication authentication, String authority) {
        return authentication.getAuthorities().stream().anyMatch(a -> authority.equals(a.getAuthority()));
    }

    private static UUID parseUuid(String value) {
        if (value == null) {
            return null;
        }
        try {
            return UUID.fromString(value);
        } catch (IllegalArgumentException ex) {
            return null;
        }
    }
}
