package com.alphaledger.auth.exception;

import org.springframework.http.HttpStatus;

import com.alphaledger.common.error.ApiException;

/** A cookie-mode auth request that failed the cross-site request checks (Origin, Sec-Fetch-Site, client header). */
public class CsrfRejectedException extends ApiException {

    public CsrfRejectedException(String reason) {
        super(HttpStatus.FORBIDDEN, "CSRF_REJECTED", "Request rejected: " + reason);
    }
}
