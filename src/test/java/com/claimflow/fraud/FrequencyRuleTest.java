package com.claimflow.fraud;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.time.LocalDate;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import com.claimflow.TestFixtures;
import com.claimflow.claim.Claim;

class FrequencyRuleTest {

    private final FrequencyRule rule = new FrequencyRule(new FraudProperties(7, 3, 30, 30));
    private final Claim claim = TestFixtures.claim(TestFixtures.policy(), LocalDate.of(2026, 6, 1),
            Instant.parse("2026-06-02T00:00:00Z"), "1000.00");

    @ParameterizedTest(name = "{0} claims in window -> flagged: {1}")
    @CsvSource({
            "1, false",
            "2, false",
            "3, true",    // exactly 3 is flagged (>= 3)
            "10, true"
    })
    void flagsThreeOrMoreClaimsInWindow(long recentClaims, boolean flagged) {
        ClaimContext context = new ClaimContext(Instant.parse("2026-06-02T00:00:00Z"), recentClaims);

        assertThat(rule.evaluate(claim, context).isPresent()).isEqualTo(flagged);
    }

    @Test
    void reasonMentionsCountAndWindow() {
        ClaimContext context = new ClaimContext(Instant.parse("2026-06-02T00:00:00Z"), 3);

        assertThat(rule.evaluate(claim, context)).hasValueSatisfying(flag -> {
            assertThat(flag.getRule()).isEqualTo(FrequencyRule.NAME);
            assertThat(flag.getReason()).contains("3 claims").contains("30 days");
        });
    }
}
