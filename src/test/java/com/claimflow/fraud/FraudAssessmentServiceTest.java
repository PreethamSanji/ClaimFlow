package com.claimflow.fraud;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.Test;

import com.claimflow.TestFixtures;
import com.claimflow.claim.Claim;
import com.claimflow.claim.ClaimRepository;
import com.claimflow.claim.FraudFlag;
import com.claimflow.observability.ClaimMetrics;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;

class FraudAssessmentServiceTest {

    private static final Instant NOW = Instant.parse("2026-06-15T12:00:00Z");
    // 30-day frequency window counted back from NOW.
    private static final Instant WINDOW_START = Instant.parse("2026-05-16T12:00:00Z");

    private final ClaimRepository claimRepository = mock(ClaimRepository.class);
    private final FraudProperties properties = new FraudProperties(7, 3, 30, 30);
    private final SimpleMeterRegistry registry = new SimpleMeterRegistry();

    private final Claim claim = TestFixtures.claim(TestFixtures.policy(), LocalDate.of(2026, 6, 1),
            Instant.parse("2026-06-02T00:00:00Z"), "1000.00");

    private FraudAssessmentService service(List<FraudRule> rules) {
        return new FraudAssessmentService(rules, claimRepository, properties, new ClaimMetrics(registry),
                Clock.fixed(NOW, ZoneOffset.UTC));
    }

    @Test
    void collectsFlagsFromEveryRuleThatFires() {
        FraudRule fires = (c, ctx) -> Optional.of(new FraudFlag("A", "rule A fired"));
        FraudRule silent = (c, ctx) -> Optional.empty();
        FraudRule alsoFires = (c, ctx) -> Optional.of(new FraudFlag("B", "rule B fired"));

        List<FraudFlag> flags = service(List.of(fires, silent, alsoFires)).assess(claim);

        assertThat(flags).extracting(FraudFlag::getRule).containsExactly("A", "B");
    }

    @Test
    void returnsNoFlagsWhenNoRuleFires() {
        assertThat(service(List.of((c, ctx) -> Optional.empty())).assess(claim)).isEmpty();
    }

    @Test
    void contextCountsCustomerClaimsInConfiguredWindow() {
        when(claimRepository.countByCustomerSince(eq(1L), eq(WINDOW_START))).thenReturn(4L);
        AtomicReference<ClaimContext> seen = new AtomicReference<>();
        FraudRule recorder = (c, ctx) -> {
            seen.set(ctx);
            return Optional.empty();
        };

        service(List.of(recorder)).assess(claim);

        assertThat(seen.get().recentClaimsForCustomer()).isEqualTo(4L);
        assertThat(seen.get().now()).isEqualTo(NOW);
    }

    @Test
    void recordsTimerAndFlagCounters() {
        FraudRule fires = (c, ctx) -> Optional.of(new FraudFlag("EARLY_CLAIM", "too soon"));

        service(List.of(fires)).assess(claim);

        assertThat(registry.get("claims.fraud.assessment").timer().count()).isEqualTo(1);
        assertThat(registry.get("claims.fraud.flags").tag("rule", "EARLY_CLAIM").counter().count()).isEqualTo(1.0);
    }

    @Test
    void realRulesWorkTogether() {
        // Claim on day 0 of the policy, for more than the limit -> two flags.
        Claim risky = TestFixtures.claim(
                TestFixtures.policy(LocalDate.of(2026, 6, 10), LocalDate.of(2027, 6, 9), "1000.00", "100.00"),
                LocalDate.of(2026, 6, 10), Instant.parse("2026-06-11T09:00:00Z"), "5000.00");
        when(claimRepository.countByCustomerSince(eq(1L), eq(WINDOW_START))).thenReturn(1L);
        List<FraudRule> rules = List.of(
                new AmountExceedsCoverageRule(),
                new EarlyClaimRule(properties),
                new FrequencyRule(properties),
                new LateReportingRule(properties));

        List<FraudFlag> flags = service(rules).assess(risky);

        assertThat(flags).extracting(FraudFlag::getRule)
                .containsExactly(AmountExceedsCoverageRule.NAME, EarlyClaimRule.NAME);
    }
}
