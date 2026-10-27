package com.alphaledger.common.event;

/**
 * An event that can never be processed as it is (no payload, a required field missing). It is not retried: the
 * consumer's error handler sends it straight to the dead-letter topic.
 */
public class InvalidEventException extends RuntimeException {

    public InvalidEventException(String message) {
        super(message);
    }
}
