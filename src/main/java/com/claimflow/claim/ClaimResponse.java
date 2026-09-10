package com.claimflow.claim;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

public record ClaimResponse(
        Long id,
        String claimNumber,
        Long policyId,
        String policyNumber,
        LocalDate incidentDate,
        Instant reportedAt,
        String description,
        BigDecimal claimedAmount,
        BigDecimal approvedAmount,
        ClaimStatus status,
        List<FraudFlagResponse> fraudFlags,
        Long version) {

    public record FraudFlagResponse(String rule, String reason) {
    }

    public static ClaimResponse from(Claim claim) {
        List<FraudFlagResponse> flags = claim.getFraudFlags().stream()
                .map(flag -> new FraudFlagResponse(flag.getRule(), flag.getReason()))
                .toList();
        return new ClaimResponse(
                claim.getId(),
                claim.getClaimNumber(),
                claim.getPolicy().getId(),
                claim.getPolicy().getPolicyNumber(),
                claim.getIncidentDate(),
                claim.getReportedAt(),
                claim.getDescription(),
                claim.getClaimedAmount(),
                claim.getApprovedAmount(),
                claim.getStatus(),
                flags,
                claim.getVersion());
    }
}
