package com.claimflow.fraud;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import jakarta.validation.constraints.Min;

/**
 * Fraud thresholds from application.yml (claimflow.fraud.*).
 * Bad values stop the app at startup instead of failing later.
 */
@Validated
@ConfigurationProperties(prefix = "claimflow.fraud")
public record FraudProperties(
        @Min(1) int earlyClaimDays,
        @Min(1) int frequencyMaxClaims,
        @Min(1) int frequencyWindowDays,
        @Min(1) int lateReportingDays) {
}
