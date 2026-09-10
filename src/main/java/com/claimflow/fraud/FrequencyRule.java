package com.claimflow.fraud;

import java.util.Optional;

import org.springframework.stereotype.Component;

import com.claimflow.claim.Claim;
import com.claimflow.claim.FraudFlag;

/** Flags customers who file many claims in a short window. */
@Component
public class FrequencyRule implements FraudRule {

    public static final String NAME = "HIGH_FREQUENCY";

    private final FraudProperties properties;

    public FrequencyRule(FraudProperties properties) {
        this.properties = properties;
    }

    @Override
    public Optional<FraudFlag> evaluate(Claim claim, ClaimContext context) {
        long count = context.recentClaimsForCustomer();
        if (count >= properties.frequencyMaxClaims()) {
            return Optional.of(new FraudFlag(NAME, "Customer filed " + count + " claims in the last "
                    + properties.frequencyWindowDays() + " days (threshold " + properties.frequencyMaxClaims() + ")"));
        }
        return Optional.empty();
    }
}
