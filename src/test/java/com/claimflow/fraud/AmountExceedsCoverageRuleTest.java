package com.claimflow.fraud;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.time.LocalDate;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import com.claimflow.TestFixtures;
import com.claimflow.claim.Claim;

class AmountExceedsCoverageRuleTest {

    private final AmountExceedsCoverageRule rule = new AmountExceedsCoverageRule();
    private final ClaimContext context = new ClaimContext(Instant.parse("2026-06-15T00:00:00Z"), 1);

    @ParameterizedTest(name = "claimed {0} on a 10,000 limit -> flagged: {1}")
    @CsvSource({
            "9999.99,  false",
            "10000.00, false",   // exactly the limit is fine
            "10000.01, true",    // one cent over is flagged
            "50000.00, true"
    })
    void flagsOnlyAmountsAboveTheLimit(String claimed, boolean flagged) {
        Claim claim = TestFixtures.claim(TestFixtures.policy(), LocalDate.of(2026, 6, 1),
                Instant.parse("2026-06-02T00:00:00Z"), claimed);

        assertThat(rule.evaluate(claim, context).isPresent()).isEqualTo(flagged);
    }

    @Test
    void flagNamesTheRuleAndAmounts() {
        Claim claim = TestFixtures.claim(TestFixtures.policy(), LocalDate.of(2026, 6, 1),
                Instant.parse("2026-06-02T00:00:00Z"), "10000.01");

        assertThat(rule.evaluate(claim, context)).hasValueSatisfying(flag -> {
            assertThat(flag.getRule()).isEqualTo(AmountExceedsCoverageRule.NAME);
            assertThat(flag.getReason()).contains("10000.01").contains("10000.00");
        });
    }
}
