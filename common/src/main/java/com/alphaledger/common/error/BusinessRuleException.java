package com.alphaledger.common.error;

import org.springframework.http.HttpStatus;

/** A well-formed request that breaks a business rule, e.g. selling more than is held. */
public class BusinessRuleException extends ApiException {

    public BusinessRuleException(String message) {
        super(HttpStatus.UNPROCESSABLE_CONTENT, "BUSINESS_RULE_VIOLATION", message);
    }
}
