package com.claimflow.fraud;

import java.math.BigDecimal;
import java.util.Optional;

import org.springframework.stereotype.Component;

import com.claimflow.claim.Claim;
import com.claimflow.claim.FraudFlag;

/** Flags claims asking for more than the policy can ever pay. */
@Component
public class AmountExceedsCoverageRule implements FraudRule {

    public static final String NAME = "AMOUNT_EXCEEDS_COVERAGE";

    @Override
    public Optional<FraudFlag> evaluate(Claim claim, ClaimContext context) {
        BigDecimal limit = claim.getPolicy().getCoverageLimit();
        if (claim.getClaimedAmount().compareTo(limit) > 0) {
            return Optional.of(new FraudFlag(NAME,
                    "Claimed amount " + claim.getClaimedAmount() + " exceeds coverage limit " + limit));
        }
        return Optional.empty();
    }
}
