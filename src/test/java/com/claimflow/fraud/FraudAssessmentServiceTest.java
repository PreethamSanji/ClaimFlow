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

class FraudAssessmentServiceTest {

    private static final Instant NOW = Instant.parse("2026-06-15T12:00:00Z");

    private final ClaimRepository claimRepository = mock(ClaimRepository.class);
    private final FraudProperties properties = new FraudProperties(7, 3, 30, 30);
    private final Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);

    private final Claim claim = TestFixtures.claim(TestFixtures.policy(), LocalDate.of(2026, 6, 1),
            Instant.parse("2026-06-02T00:00:00Z"), "1000.00");

    @Test
    void collectsFlagsFromEveryRuleThatFires() {
        FraudRule fires = (c, ctx) -> Optional.of(new FraudFlag("A", "rule A fired"));
        FraudRule silent = (c, ctx) -> Optional.empty();
        FraudRule alsoFires = (c, ctx) -> Optional.of(new FraudFlag("B", "rule B fired"));
        FraudAssessmentService service =
                new FraudAssessmentService(List.of(fires, silent, alsoFires), claimRepository, properties, clock);

        List<FraudFlag> flags = service.assess(claim);

        assertThat(flags).extracting(FraudFlag::getRule).containsExactly("A", "B");
    }

    @Test
    void returnsNoFlagsWhenNoRuleFires() {
        FraudAssessmentService service = new FraudAssessmentService(
                List.of((c, ctx) -> Optional.empty()), claimRepository, properties, clock);

        assertThat(service.assess(claim)).isEmpty();
    }

    @Test
    void contextCountsCustomerClaimsInConfiguredWindow() {
        // Window is 30 days back from NOW.
        when(claimRepository.countByCustomerSince(eq(1L), eq(Instant.parse("2026-05-16T12:00:00Z"))))
                .thenReturn(4L);
        AtomicReference<ClaimContext> seen = new AtomicReference<>();
        FraudRule spy = (c, ctx) -> {
            seen.set(ctx);
            return Optional.empty();
        };

        new FraudAssessmentService(List.of(spy), claimRepository, properties, clock).assess(claim);

        assertThat(seen.get().recentClaimsForCustomer()).isEqualTo(4L);
        assertThat(seen.get().now()).isEqualTo(NOW);
    }

    @Test
    void realRulesWorkTogether() {
        // Claim on day 0 of the policy, for more than the limit -> two flags.
        Claim risky = TestFixtures.claim(
                TestFixtures.policy(LocalDate.of(2026, 6, 10), LocalDate.of(2027, 6, 9), "1000.00", "100.00"),
                LocalDate.of(2026, 6, 10), Instant.parse("2026-06-11T09:00:00Z"), "5000.00");
        when(claimRepository.countByCustomerSince(eq(1L), eq(Instant.parse("2026-05-16T12:00:00Z"))))
                .thenReturn(1L);
        List<FraudRule> rules = List.of(
                new AmountExceedsCoverageRule(),
                new EarlyClaimRule(properties),
                new FrequencyRule(properties),
                new LateReportingRule(properties));

        List<FraudFlag> flags = new FraudAssessmentService(rules, claimRepository, properties, clock).assess(risky);

        assertThat(flags).extracting(FraudFlag::getRule)
                .containsExactly(AmountExceedsCoverageRule.NAME, EarlyClaimRule.NAME);
    }
}
