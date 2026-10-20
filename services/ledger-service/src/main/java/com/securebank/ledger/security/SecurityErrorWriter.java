package com.securebank.ledger.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.securebank.ledger.dto.ApiError;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.time.Instant;
import org.springframework.http.MediaType;

/** Writes the standard ApiError JSON for failures raised inside the security filter chain. */
final class SecurityErrorWriter {

    private SecurityErrorWriter() {
    }

    static void write(ObjectMapper mapper, HttpServletRequest request, HttpServletResponse response,
                      int status, String code, String message) throws IOException {
        response.setStatus(status);
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding("UTF-8");
        mapper.writeValue(response.getOutputStream(),
                new ApiError(Instant.now(), status, code, message, request.getRequestURI(), null));
    }
}
