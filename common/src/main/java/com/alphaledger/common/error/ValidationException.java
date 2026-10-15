package com.alphaledger.common.error;

import org.springframework.http.HttpStatus;

/** Input that passes bean validation but fails a domain check, e.g. an ISIN with a wrong check digit. */
public class ValidationException extends ApiException {

    public ValidationException(String message) {
        super(HttpStatus.BAD_REQUEST, "VALIDATION_ERROR", message);
    }
}
