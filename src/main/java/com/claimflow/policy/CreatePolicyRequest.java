package com.claimflow.policy;

import java.math.BigDecimal;
import java.time.LocalDate;

import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.PositiveOrZero;

public record CreatePolicyRequest(
        @NotNull Long customerId,
        @NotNull PolicyType type,
        @NotNull @Positive @Digits(integer = 13, fraction = 2) BigDecimal coverageLimit,
        @NotNull @PositiveOrZero @Digits(integer = 13, fraction = 2) BigDecimal deductible,
        @NotNull LocalDate startDate,
        @NotNull LocalDate endDate) {
}
