package com.securebank.transaction.security;

import com.securebank.transaction.config.SecurityProperties;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.stereotype.Component;

@Component
public class CallerResolver {

    static final String OPERATOR_AUTHORITY = "ROLE_TRANSFER_OPERATOR";

    private final SecurityProperties props;

    public CallerResolver(SecurityProperties props) {
        this.props = props;
    }

    public Caller resolve(Authentication authentication) {
        if (!(authentication instanceof JwtAuthenticationToken jwt)) {
            throw new AccessDeniedException("JWT authentication required");
        }
        String claim = jwt.getToken().getClaimAsString(props.customerIdClaim());
        String customerId = (claim == null || claim.isBlank()) ? null : claim;
        boolean operator = authentication.getAuthorities().stream()
                .anyMatch(a -> OPERATOR_AUTHORITY.equals(a.getAuthority()));
        return new Caller(authentication.getName(), customerId, operator);
    }
}
