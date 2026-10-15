package com.alphaledger.auth.exception;

import org.springframework.http.HttpStatus;

import com.alphaledger.common.error.ApiException;

public class InvalidRefreshTokenException extends ApiException {

    public InvalidRefreshTokenException() {
        super(HttpStatus.UNAUTHORIZED, "INVALID_REFRESH_TOKEN", "Refresh token is invalid, expired or revoked");
    }
}
