package com.claimflow.policy;

import java.math.BigDecimal;
import java.time.LocalDate;

public record PolicyResponse(
        Long id,
        String policyNumber,
        Long customerId,
        PolicyType type,
        BigDecimal coverageLimit,
        BigDecimal deductible,
        LocalDate startDate,
        LocalDate endDate,
        PolicyStatus status) {

    public static PolicyResponse from(Policy policy) {
        return new PolicyResponse(
                policy.getId(),
                policy.getPolicyNumber(),
                policy.getCustomer().getId(),
                policy.getType(),
                policy.getCoverageLimit(),
                policy.getDeductible(),
                policy.getStartDate(),
                policy.getEndDate(),
                policy.getStatus());
    }
}
