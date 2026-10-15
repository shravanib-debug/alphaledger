package com.alphaledger.auth.exception;

import org.springframework.http.HttpStatus;

import com.alphaledger.common.error.ApiException;

/** Same message for unknown email, wrong password and disabled account, so accounts cannot be enumerated. */
public class InvalidCredentialsException extends ApiException {

    public InvalidCredentialsException() {
        super(HttpStatus.UNAUTHORIZED, "INVALID_CREDENTIALS", "Invalid email or password");
    }
}
