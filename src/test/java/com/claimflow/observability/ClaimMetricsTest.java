package com.claimflow.observability;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

import com.claimflow.claim.ClaimStatus;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;

class ClaimMetricsTest {

    private final SimpleMeterRegistry registry = new SimpleMeterRegistry();
    private final ClaimMetrics metrics = new ClaimMetrics(registry);

    @Test
    void countsCreatedClaims() {
        metrics.claimCreated();
        metrics.claimCreated();

        assertThat(registry.get("claims.filed").counter().count()).isEqualTo(2.0);
    }

    @Test
    void countsTransitionsPerFromAndTo() {
        metrics.transition(ClaimStatus.FNOL, ClaimStatus.UNDER_REVIEW);
        metrics.transition(ClaimStatus.FNOL, ClaimStatus.UNDER_REVIEW);
        metrics.transition(ClaimStatus.UNDER_REVIEW, ClaimStatus.APPROVED);

        assertThat(registry.get("claims.transitions").tags("from", "FNOL", "to", "UNDER_REVIEW").counter().count())
                .isEqualTo(2.0);
        assertThat(registry.get("claims.transitions").tags("from", "UNDER_REVIEW", "to", "APPROVED").counter().count())
                .isEqualTo(1.0);
    }

    @Test
    void countsFraudFlagsPerRule() {
        metrics.fraudFlagRaised("EARLY_CLAIM");

        assertThat(registry.get("claims.fraud.flags").tag("rule", "EARLY_CLAIM").counter().count()).isEqualTo(1.0);
    }

    @Test
    void timesFraudAssessmentAndReturnsResult() {
        String result = metrics.timeFraudAssessment(() -> "done");

        assertThat(result).isEqualTo("done");
        assertThat(registry.get("claims.fraud.assessment").timer().count()).isEqualTo(1);
    }
}
