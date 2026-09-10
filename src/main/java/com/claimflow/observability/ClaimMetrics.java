package com.claimflow.observability;

import java.util.function.Supplier;

import org.springframework.stereotype.Component;

import com.claimflow.claim.ClaimStatus;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;

/**
 * Business metrics. Micrometer names use dots; Prometheus shows them with
 * underscores, e.g. "claims.created" -> claims_created_total.
 */
@Component
public class ClaimMetrics {

    private final MeterRegistry registry;
    private final Counter claimsCreated;
    private final Timer fraudAssessmentTimer;

    public ClaimMetrics(MeterRegistry registry) {
        this.registry = registry;
        this.claimsCreated = Counter.builder("claims.created")
                .description("Claims filed (FNOL)")
                .register(registry);
        this.fraudAssessmentTimer = Timer.builder("claims.fraud.assessment")
                .description("Time to run all fraud rules on one claim")
                .register(registry);
    }

    public void claimCreated() {
        claimsCreated.increment();
    }

    // Tags have few possible values (6 statuses), so this is safe for Prometheus.
    public void transition(ClaimStatus from, ClaimStatus to) {
        Counter.builder("claims.transitions")
                .description("Claim status changes")
                .tag("from", from.name())
                .tag("to", to.name())
                .register(registry)
                .increment();
    }

    public void fraudFlagRaised(String rule) {
        Counter.builder("claims.fraud.flags")
                .description("Fraud flags raised, per rule")
                .tag("rule", rule)
                .register(registry)
                .increment();
    }

    public <T> T timeFraudAssessment(Supplier<T> work) {
        return fraudAssessmentTimer.record(work);
    }
}
