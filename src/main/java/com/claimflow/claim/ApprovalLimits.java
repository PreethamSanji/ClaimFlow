package com.claimflow.claim;

import java.math.BigDecimal;

/** Payout math. All money is BigDecimal so there are no rounding errors. */
public final class ApprovalLimits {

    private ApprovalLimits() {
    }

    /**
     * Most we can pay = claimed minus deductible (never below 0), capped at the coverage limit.
     * Example: claimed 1,200, deductible 500, limit 10,000 -> 700.
     */
    public static BigDecimal maxApprovable(BigDecimal claimedAmount, BigDecimal deductible, BigDecimal coverageLimit) {
        BigDecimal afterDeductible = claimedAmount.subtract(deductible).max(BigDecimal.ZERO);
        return afterDeductible.min(coverageLimit);
    }
}
