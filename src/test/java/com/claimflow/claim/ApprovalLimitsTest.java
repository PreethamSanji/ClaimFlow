package com.claimflow.claim;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class ApprovalLimitsTest {

    @ParameterizedTest(name = "claimed {0}, deductible {1}, limit {2} -> max {3}")
    @CsvSource({
            // claimed,  deductible, coverage,  expected max
            "1200.00,    500.00,     10000.00,  700.00",    // normal case
            "1000.00,    0.00,       10000.00,  1000.00",   // no deductible
            "500.00,     500.00,     10000.00,  0.00",      // claimed == deductible
            "400.00,     500.00,     10000.00,  0.00",      // below deductible, never negative
            "20000.00,   500.00,     10000.00,  10000.00",  // capped at coverage limit
            "10500.00,   500.00,     10000.00,  10000.00",  // exactly hits the limit
            "10500.01,   500.00,     10000.00,  10000.00",  // one cent over the limit
            "0.01,       0.00,       10000.00,  0.01"       // smallest claim
    })
    void maxApprovableAmount(String claimed, String deductible, String coverage, String expected) {
        BigDecimal max = ApprovalLimits.maxApprovable(
                new BigDecimal(claimed), new BigDecimal(deductible), new BigDecimal(coverage));

        // isEqualByComparingTo ignores scale, so 0 == 0.00.
        assertThat(max).isEqualByComparingTo(expected);
    }
}
