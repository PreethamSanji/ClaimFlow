package com.claimflow.claim;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.claimflow.TestFixtures;
import com.claimflow.common.BusinessRuleViolationException;
import com.claimflow.common.ResourceNotFoundException;
import com.claimflow.fraud.FraudAssessmentService;
import com.claimflow.policy.PolicyRepository;

@ExtendWith(MockitoExtension.class)
class ClaimServiceTransitionTest {

    private static final Instant NOW = Instant.parse("2026-06-15T12:00:00Z");

    @Mock
    ClaimRepository claimRepository;

    @Mock
    ClaimEventRepository claimEventRepository;

    @Mock
    PolicyRepository policyRepository;

    @Mock
    FraudAssessmentService fraudAssessmentService;

    ClaimService service;

    @BeforeEach
    void setUp() {
        service = ClaimServiceTestSupport.newService(claimRepository, claimEventRepository, policyRepository,
                fraudAssessmentService, Clock.fixed(NOW, ZoneOffset.UTC));
    }

    /** Policy: coverage 10,000, deductible 500. Claim for 1,200 -> max payout 700. */
    private Claim givenClaim(ClaimStatus status) {
        Claim claim = TestFixtures.claimInStatus(TestFixtures.policy(), "1200.00", status);
        when(claimRepository.findById(100L)).thenReturn(Optional.of(claim));
        return claim;
    }

    private static TransitionRequest to(ClaimStatus status) {
        return new TransitionRequest(status, "because", null);
    }

    private static TransitionRequest approve(String amount) {
        return new TransitionRequest(ClaimStatus.APPROVED, "damage confirmed", new BigDecimal(amount));
    }

    @Test
    void validTransitionChangesStatusAndWritesAuditEvent() {
        givenClaim(ClaimStatus.UNDER_REVIEW);

        ClaimResponse response = service.transition(100L, to(ClaimStatus.REJECTED), "adjuster.kim");

        assertThat(response.status()).isEqualTo(ClaimStatus.REJECTED);
        ArgumentCaptor<ClaimEvent> event = ArgumentCaptor.forClass(ClaimEvent.class);
        verify(claimEventRepository).save(event.capture());
        assertThat(event.getValue().getFromStatus()).isEqualTo(ClaimStatus.UNDER_REVIEW);
        assertThat(event.getValue().getToStatus()).isEqualTo(ClaimStatus.REJECTED);
        assertThat(event.getValue().getReason()).isEqualTo("because");
        assertThat(event.getValue().getActor()).isEqualTo("adjuster.kim");
        assertThat(event.getValue().getOccurredAt()).isEqualTo(NOW);
        verify(claimRepository).saveAndFlush(any(Claim.class));
    }

    @Test
    void invalidTransitionThrowsAndWritesNothing() {
        givenClaim(ClaimStatus.PAID);

        assertThatThrownBy(() -> service.transition(100L, to(ClaimStatus.APPROVED), "adjuster"))
                .isInstanceOf(InvalidClaimTransitionException.class);
        verify(claimEventRepository, never()).save(any());
        verify(claimRepository, never()).saveAndFlush(any());
    }

    @Test
    void approveWithExactMaximumSucceeds() {
        givenClaim(ClaimStatus.UNDER_REVIEW);

        ClaimResponse response = service.transition(100L, approve("700.00"), "adjuster");

        assertThat(response.status()).isEqualTo(ClaimStatus.APPROVED);
        assertThat(response.approvedAmount()).isEqualByComparingTo("700.00");
    }

    @Test
    void approveAboveClaimedMinusDeductibleIsRejected() {
        givenClaim(ClaimStatus.UNDER_REVIEW);

        assertThatThrownBy(() -> service.transition(100L, approve("700.01"), "adjuster"))
                .isInstanceOf(BusinessRuleViolationException.class)
                .hasMessageContaining("maximum payable 700.00");
        verify(claimEventRepository, never()).save(any());
    }

    @Test
    void approveAboveCoverageLimitIsRejected() {
        // Claim 50,000 on a 10,000 policy: max payout is the limit.
        Claim claim = TestFixtures.claimInStatus(TestFixtures.policy(), "50000.00", ClaimStatus.UNDER_REVIEW);
        when(claimRepository.findById(100L)).thenReturn(Optional.of(claim));

        assertThatThrownBy(() -> service.transition(100L, approve("10000.01"), "adjuster"))
                .isInstanceOf(BusinessRuleViolationException.class)
                .hasMessageContaining("maximum payable 10000.00");
    }

    @Test
    void approveWithoutAmountIsRejected() {
        givenClaim(ClaimStatus.UNDER_REVIEW);

        assertThatThrownBy(() -> service.transition(100L, to(ClaimStatus.APPROVED), "adjuster"))
                .isInstanceOf(BusinessRuleViolationException.class)
                .hasMessageContaining("required");
    }

    @Test
    void approvedAmountOnNonApprovalIsRejected() {
        givenClaim(ClaimStatus.UNDER_REVIEW);
        TransitionRequest request = new TransitionRequest(ClaimStatus.REJECTED, "no", new BigDecimal("10.00"));

        assertThatThrownBy(() -> service.transition(100L, request, "adjuster"))
                .isInstanceOf(BusinessRuleViolationException.class)
                .hasMessageContaining("only allowed");
    }

    @Test
    void approvingFromWrongStatusIsA409NotA422() {
        // State machine check runs before the amount check.
        givenClaim(ClaimStatus.FNOL);

        assertThatThrownBy(() -> service.transition(100L, approve("999999.00"), "adjuster"))
                .isInstanceOf(InvalidClaimTransitionException.class);
    }

    @Test
    void enteringReviewWithFraudFlagsAutoFlagsTheClaim() {
        Claim claim = givenClaim(ClaimStatus.FNOL);
        when(fraudAssessmentService.assess(claim))
                .thenReturn(List.of(new FraudFlag("EARLY_CLAIM", "Incident 2 day(s) after policy start")));

        ClaimResponse response = service.transition(100L, to(ClaimStatus.UNDER_REVIEW), "adjuster.kim");

        assertThat(response.status()).isEqualTo(ClaimStatus.FLAGGED_FOR_INVESTIGATION);
        assertThat(response.fraudFlags())
                .extracting(ClaimResponse.FraudFlagResponse::rule)
                .containsExactly("EARLY_CLAIM");

        // Two audit rows: the human move, then the automatic flag.
        ArgumentCaptor<ClaimEvent> events = ArgumentCaptor.forClass(ClaimEvent.class);
        verify(claimEventRepository, times(2)).save(events.capture());
        ClaimEvent review = events.getAllValues().get(0);
        ClaimEvent flagged = events.getAllValues().get(1);
        assertThat(review.getToStatus()).isEqualTo(ClaimStatus.UNDER_REVIEW);
        assertThat(review.getActor()).isEqualTo("adjuster.kim");
        assertThat(flagged.getFromStatus()).isEqualTo(ClaimStatus.UNDER_REVIEW);
        assertThat(flagged.getToStatus()).isEqualTo(ClaimStatus.FLAGGED_FOR_INVESTIGATION);
        assertThat(flagged.getActor()).isEqualTo(ClaimService.FRAUD_ENGINE_ACTOR);
        assertThat(flagged.getReason()).contains("EARLY_CLAIM: Incident 2 day(s) after policy start");
    }

    @Test
    void enteringReviewWithoutFlagsStaysUnderReview() {
        Claim claim = givenClaim(ClaimStatus.FNOL);
        when(fraudAssessmentService.assess(claim)).thenReturn(List.of());

        ClaimResponse response = service.transition(100L, to(ClaimStatus.UNDER_REVIEW), "adjuster");

        assertThat(response.status()).isEqualTo(ClaimStatus.UNDER_REVIEW);
        assertThat(response.fraudFlags()).isEmpty();
        verify(claimEventRepository, times(1)).save(any());
    }

    @Test
    void returningFromInvestigationDoesNotRerunFraudRules() {
        givenClaim(ClaimStatus.FLAGGED_FOR_INVESTIGATION);

        ClaimResponse response = service.transition(100L, to(ClaimStatus.UNDER_REVIEW), "investigator");

        assertThat(response.status()).isEqualTo(ClaimStatus.UNDER_REVIEW);
        verifyNoInteractions(fraudAssessmentService);
    }

    @Test
    void unknownClaimIsNotFound() {
        when(claimRepository.findById(100L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.transition(100L, to(ClaimStatus.UNDER_REVIEW), "adjuster"))
                .isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    void eventsForUnknownClaimAreNotFound() {
        when(claimRepository.existsById(100L)).thenReturn(false);

        assertThatThrownBy(() -> service.events(100L))
                .isInstanceOf(ResourceNotFoundException.class);
    }
}
