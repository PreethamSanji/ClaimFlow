package com.claimflow.fraud;

import java.time.temporal.ChronoUnit;
import java.util.Optional;

import org.springframework.stereotype.Component;

import com.claimflow.claim.Claim;
import com.claimflow.claim.FraudFlag;

/** Flags incidents very soon after the policy started (someone may have insured a known loss). */
@Component
public class EarlyClaimRule implements FraudRule {

    public static final String NAME = "EARLY_CLAIM";

    private final FraudProperties properties;

    public EarlyClaimRule(FraudProperties properties) {
        this.properties = properties;
    }

    @Override
    public Optional<FraudFlag> evaluate(Claim claim, ClaimContext context) {
        long days = ChronoUnit.DAYS.between(claim.getPolicy().getStartDate(), claim.getIncidentDate());
        // "Within 7 days" includes day 7.
        if (days <= properties.earlyClaimDays()) {
            return Optional.of(new FraudFlag(NAME, "Incident " + days + " day(s) after policy start (threshold "
                    + properties.earlyClaimDays() + ")"));
        }
        return Optional.empty();
    }
}
