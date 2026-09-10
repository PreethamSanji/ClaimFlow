package com.claimflow.fraud;

import java.util.Optional;

import com.claimflow.claim.Claim;
import com.claimflow.claim.FraudFlag;

/**
 * One fraud check. Each rule is its own Spring bean, so adding a rule means
 * adding a class. No other code changes.
 */
public interface FraudRule {

    Optional<FraudFlag> evaluate(Claim claim, ClaimContext context);
}
