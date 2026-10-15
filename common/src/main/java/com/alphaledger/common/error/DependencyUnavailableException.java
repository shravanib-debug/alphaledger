package com.alphaledger.common.error;

import org.springframework.http.HttpStatus;

/** A service this request depends on did not answer; the caller may retry later. */
public class DependencyUnavailableException extends ApiException {

    public DependencyUnavailableException(String message) {
        super(HttpStatus.SERVICE_UNAVAILABLE, "DEPENDENCY_UNAVAILABLE", message);
    }
}
