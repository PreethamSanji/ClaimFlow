package com.claimflow.fraud;

import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.Optional;

import org.springframework.stereotype.Component;

import com.claimflow.claim.Claim;
import com.claimflow.claim.FraudFlag;

/** Flags claims reported long after the incident (evidence is harder to check). */
@Component
public class LateReportingRule implements FraudRule {

    public static final String NAME = "LATE_REPORTING";

    private final FraudProperties properties;

    public LateReportingRule(FraudProperties properties) {
        this.properties = properties;
    }

    @Override
    public Optional<FraudFlag> evaluate(Claim claim, ClaimContext context) {
        LocalDate reportedDate = LocalDate.ofInstant(claim.getReportedAt(), ZoneOffset.UTC);
        long days = ChronoUnit.DAYS.between(claim.getIncidentDate(), reportedDate);
        // "More than 30 days": day 30 is fine, day 31 is flagged.
        if (days > properties.lateReportingDays()) {
            return Optional.of(new FraudFlag(NAME, "Reported " + days + " days after the incident (threshold "
                    + properties.lateReportingDays() + ")"));
        }
        return Optional.empty();
    }
}
