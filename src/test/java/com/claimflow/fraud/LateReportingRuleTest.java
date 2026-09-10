package com.claimflow.fraud;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.time.LocalDate;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import com.claimflow.TestFixtures;
import com.claimflow.claim.Claim;

class LateReportingRuleTest {

    private static final LocalDate INCIDENT = LocalDate.of(2026, 3, 1);

    private final LateReportingRule rule = new LateReportingRule(new FraudProperties(7, 3, 30, 30));
    private final ClaimContext context = new ClaimContext(Instant.parse("2026-06-15T00:00:00Z"), 1);

    @ParameterizedTest(name = "reported at {0} -> flagged: {1}")
    @CsvSource({
            "2026-03-01T10:00:00Z, false",   // same day
            "2026-03-31T23:59:59Z, false",   // day 30, last second: not "more than 30"
            "2026-04-01T00:00:00Z, true",    // day 31, first second: flagged
            "2026-06-01T00:00:00Z, true"
    })
    void flagsReportsMoreThanThirtyDaysAfterIncident(String reportedAt, boolean flagged) {
        Claim claim = TestFixtures.claim(TestFixtures.policy(), INCIDENT, Instant.parse(reportedAt), "1000.00");

        assertThat(rule.evaluate(claim, context).isPresent()).isEqualTo(flagged);
    }
}
