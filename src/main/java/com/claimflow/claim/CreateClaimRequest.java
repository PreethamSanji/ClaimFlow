package com.claimflow.claim;

import java.math.BigDecimal;
import java.time.LocalDate;

import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

// Future incident dates are a business rule (422), checked in ClaimService, not here.
public record CreateClaimRequest(
        @NotNull Long policyId,
        @NotNull LocalDate incidentDate,
        @NotBlank @Size(max = 2000) String description,
        @NotNull @Positive @Digits(integer = 13, fraction = 2) BigDecimal claimedAmount) {
}
