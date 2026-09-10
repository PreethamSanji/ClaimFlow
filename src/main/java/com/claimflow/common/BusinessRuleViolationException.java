package com.claimflow.common;

/** Request is well-formed but breaks a business rule. Mapped to HTTP 422. */
public class BusinessRuleViolationException extends RuntimeException {

    public BusinessRuleViolationException(String message) {
        super(message);
    }
}
