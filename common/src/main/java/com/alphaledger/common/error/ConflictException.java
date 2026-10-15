package com.alphaledger.common.error;

import org.springframework.http.HttpStatus;

/** The request conflicts with current state, e.g. a duplicate trade reference. */
public class ConflictException extends ApiException {

    public ConflictException(String message) {
        super(HttpStatus.CONFLICT, "CONFLICT", message);
    }
}
