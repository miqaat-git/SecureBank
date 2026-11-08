package com.securebank.customer.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.securebank.customer.dto.ErrorResponse;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import java.io.IOException;
import java.time.Instant;

import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;

/** Writes the standard JSON error body for failures that happen inside the security filter chain. */
final class ApiErrorWriter {

    private ApiErrorWriter() {
    }

    static void write(ObjectMapper objectMapper, HttpServletRequest request, HttpServletResponse response,
            HttpStatus status, String message) throws IOException {
        response.setStatus(status.value());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding("UTF-8");
        ErrorResponse body = new ErrorResponse(
                Instant.now(), status.value(), status.getReasonPhrase(), message, request.getRequestURI(), null);
        objectMapper.writeValue(response.getWriter(), body);
    }
}
