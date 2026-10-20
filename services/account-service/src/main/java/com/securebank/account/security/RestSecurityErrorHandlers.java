package com.securebank.account.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.securebank.account.exception.ApiError;
import com.securebank.account.exception.ErrorCode;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.time.Clock;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.security.web.access.AccessDeniedHandler;
import org.springframework.stereotype.Component;

/** Produces the standard JSON error body for 401 and 403 responses raised inside the security filter chain. */
@Component
public class RestSecurityErrorHandlers implements AuthenticationEntryPoint, AccessDeniedHandler {

    private final ObjectMapper objectMapper;
    private final Clock clock;

    public RestSecurityErrorHandlers(ObjectMapper objectMapper, Clock clock) {
        this.objectMapper = objectMapper;
        this.clock = clock;
    }

    @Override
    public void commence(HttpServletRequest request, HttpServletResponse response,
                         AuthenticationException authException) throws IOException {
        response.setHeader(HttpHeaders.WWW_AUTHENTICATE, "Bearer");
        write(request, response, HttpStatus.UNAUTHORIZED, ErrorCode.AUTHENTICATION_REQUIRED,
                "A valid bearer token is required to access this resource.");
    }

    @Override
    public void handle(HttpServletRequest request, HttpServletResponse response,
                       AccessDeniedException accessDeniedException) throws IOException {
        write(request, response, HttpStatus.FORBIDDEN, ErrorCode.ACCESS_DENIED,
                "You do not have permission to perform this operation.");
    }

    private void write(HttpServletRequest request, HttpServletResponse response, HttpStatus status,
                       ErrorCode code, String message) throws IOException {
        response.setStatus(status.value());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding("UTF-8");
        ApiError body = ApiError.of(clock.instant(), status, code.name(), message, request.getRequestURI());
        objectMapper.writeValue(response.getOutputStream(), body);
    }
}
