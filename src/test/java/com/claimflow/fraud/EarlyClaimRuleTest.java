package com.claimflow.fraud;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.time.LocalDate;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import com.claimflow.TestFixtures;
import com.claimflow.claim.Claim;

class EarlyClaimRuleTest {

    private static final LocalDate POLICY_START = LocalDate.of(2026, 1, 1);

    private final EarlyClaimRule rule = new EarlyClaimRule(new FraudProperties(7, 3, 30, 30));
    private final ClaimContext context = new ClaimContext(Instant.parse("2026-06-15T00:00:00Z"), 1);

    @ParameterizedTest(name = "incident {0} days after start -> flagged: {1}")
    @CsvSource({
            "0, true",    // same day as start
            "6, true",
            "7, true",    // exactly 7 days: still "within 7 days"
            "8, false",   // first day that is not flagged
            "90, false"
    })
    void flagsIncidentsWithinSevenDaysOfStart(int daysAfterStart, boolean flagged) {
        LocalDate incident = POLICY_START.plusDays(daysAfterStart);
        Claim claim = TestFixtures.claim(
                TestFixtures.policy(POLICY_START, LocalDate.of(2026, 12, 31), "10000.00", "500.00"),
                incident, Instant.parse("2026-06-14T00:00:00Z"), "1000.00");

        assertThat(rule.evaluate(claim, context).isPresent()).isEqualTo(flagged);
    }

    @Test
    void usesConfiguredThreshold() {
        // Threshold 3: an incident on day 5 is no longer "early".
        EarlyClaimRule strictRule = new EarlyClaimRule(new FraudProperties(3, 3, 30, 30));
        Claim claim = TestFixtures.claim(
                TestFixtures.policy(POLICY_START, LocalDate.of(2026, 12, 31), "10000.00", "500.00"),
                POLICY_START.plusDays(5), Instant.parse("2026-01-06T12:00:00Z"), "1000.00");

        assertThat(strictRule.evaluate(claim, context)).isEmpty();
    }
}
