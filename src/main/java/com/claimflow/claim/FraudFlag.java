package com.claimflow.claim;

import java.util.Objects;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;

/**
 * A fraud rule hit. Stored as a row in claim_fraud_flag (value object, no own id).
 */
@Embeddable
public class FraudFlag {

    @Column(name = "rule_name", nullable = false, length = 60)
    private String rule;

    @Column(name = "reason", nullable = false, length = 500)
    private String reason;

    protected FraudFlag() {
    }

    public FraudFlag(String rule, String reason) {
        this.rule = rule;
        this.reason = reason;
    }

    public String getRule() {
        return rule;
    }

    public String getReason() {
        return reason;
    }

    // Value object: equal when rule and reason match.
    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof FraudFlag other)) {
            return false;
        }
        return Objects.equals(rule, other.rule) && Objects.equals(reason, other.reason);
    }

    @Override
    public int hashCode() {
        return Objects.hash(rule, reason);
    }

    @Override
    public String toString() {
        return rule + ": " + reason;
    }
}
