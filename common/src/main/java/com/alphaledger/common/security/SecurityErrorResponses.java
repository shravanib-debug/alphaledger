package com.alphaledger.common.security;

import java.io.IOException;
import java.time.Instant;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.security.web.access.AccessDeniedHandler;

import tools.jackson.databind.json.JsonMapper;

import com.alphaledger.common.error.ErrorResponse;
import com.alphaledger.common.web.CorrelationId;

/** Writes 401/403 responses raised by the security filter chain in the standard {@link ErrorResponse} format. */
public final class SecurityErrorResponses {

    private final JsonMapper jsonMapper;

    public SecurityErrorResponses(JsonMapper jsonMapper) {
        this.jsonMapper = jsonMapper;
    }

    public AuthenticationEntryPoint authenticationEntryPoint() {
        return (request, response, ex) -> write(request, response, HttpStatus.UNAUTHORIZED, "UNAUTHORIZED",
                "Missing, invalid or expired access token");
    }

    public AccessDeniedHandler accessDeniedHandler() {
        return (request, response, ex) -> write(request, response, HttpStatus.FORBIDDEN, "FORBIDDEN",
                "Your role does not allow this action");
    }

    private void write(HttpServletRequest request, HttpServletResponse response, HttpStatus status, String error,
                       String message) throws IOException {
        ErrorResponse body = new ErrorResponse(Instant.now(), status.value(), error, message,
                request.getRequestURI(), CorrelationId.current());
        response.setStatus(status.value());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        if (status == HttpStatus.UNAUTHORIZED) {
            response.setHeader("WWW-Authenticate", "Bearer");
        }
        jsonMapper.writeValue(response.getOutputStream(), body);
    }
}
