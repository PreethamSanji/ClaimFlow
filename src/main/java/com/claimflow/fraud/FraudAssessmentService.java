package com.claimflow.fraud;

import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Optional;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import com.claimflow.claim.Claim;
import com.claimflow.claim.ClaimRepository;
import com.claimflow.claim.FraudFlag;

/** Runs every FraudRule bean against a claim and collects the flags. */
@Service
public class FraudAssessmentService {

    private static final Logger log = LoggerFactory.getLogger(FraudAssessmentService.class);

    // Spring injects every bean that implements FraudRule.
    private final List<FraudRule> rules;
    private final ClaimRepository claimRepository;
    private final FraudProperties properties;
    private final Clock clock;

    public FraudAssessmentService(List<FraudRule> rules, ClaimRepository claimRepository,
                                  FraudProperties properties, Clock clock) {
        this.rules = rules;
        this.claimRepository = claimRepository;
        this.properties = properties;
        this.clock = clock;
    }

    public List<FraudFlag> assess(Claim claim) {
        ClaimContext context = buildContext(claim);
        List<FraudFlag> flags = rules.stream()
                .map(rule -> rule.evaluate(claim, context))
                .flatMap(Optional::stream)
                .toList();
        if (!flags.isEmpty()) {
            log.info("Fraud rules raised {} flag(s): {}", flags.size(), flags);
        }
        return flags;
    }

    private ClaimContext buildContext(Claim claim) {
        Instant now = Instant.now(clock);
        Instant windowStart = now.minus(properties.frequencyWindowDays(), ChronoUnit.DAYS);
        Long customerId = claim.getPolicy().getCustomer().getId();
        long recentClaims = claimRepository.countByCustomerSince(customerId, windowStart);
        return new ClaimContext(now, recentClaims);
    }
}
