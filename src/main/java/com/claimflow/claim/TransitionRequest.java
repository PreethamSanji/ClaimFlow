package com.claimflow.claim;

import java.math.BigDecimal;

import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

/** approvedAmount is required only when toStatus is APPROVED (checked in ClaimService). */
public record TransitionRequest(
        @NotNull ClaimStatus toStatus,
        @NotBlank @Size(max = 1000) String reason,
        @Positive @Digits(integer = 13, fraction = 2) BigDecimal approvedAmount) {
}
