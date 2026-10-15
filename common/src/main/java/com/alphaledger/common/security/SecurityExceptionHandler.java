package com.alphaledger.common.security;

import java.time.Instant;

import jakarta.servlet.http.HttpServletRequest;

import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import com.alphaledger.common.error.ErrorResponse;
import com.alphaledger.common.web.CorrelationId;

/**
 * Turns {@code @PreAuthorize} denials into 403 responses. Ordered before the global handler so its
 * catch-all {@code Exception} mapping does not report them as 500s.
 */
@RestControllerAdvice
@Order(Ordered.HIGHEST_PRECEDENCE)
public class SecurityExceptionHandler {

    @ExceptionHandler(AccessDeniedException.class)
    public ResponseEntity<ErrorResponse> handleAccessDenied(AccessDeniedException ex, HttpServletRequest request) {
        ErrorResponse body = new ErrorResponse(Instant.now(), HttpStatus.FORBIDDEN.value(), "FORBIDDEN",
                "Your role does not allow this action", request.getRequestURI(), CorrelationId.current());
        return ResponseEntity.status(HttpStatus.FORBIDDEN).body(body);
    }
}
