package com.claimflow.claim;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
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
import com.claimflow.policy.Policy;
import com.claimflow.policy.PolicyRepository;
import com.claimflow.policy.PolicyStatus;

@ExtendWith(MockitoExtension.class)
class ClaimServiceFnolTest {

    // "Today" for these tests is 2026-06-15.
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

    private static CreateClaimRequest request(LocalDate incidentDate) {
        return new CreateClaimRequest(10L, incidentDate, "Hail damage", new BigDecimal("2500.00"));
    }

    @Test
    void validClaimIsSavedAsFnolWithAuditEvent() {
        Policy policy = TestFixtures.policy();
        when(policyRepository.findById(10L)).thenReturn(Optional.of(policy));
        when(claimRepository.nextClaimNumberSequence()).thenReturn(45L);
        when(claimRepository.save(any(Claim.class))).thenAnswer(inv -> inv.getArgument(0));

        ClaimResponse response = service.fileClaim(request(LocalDate.of(2026, 6, 10)), "agent.smith");

        assertThat(response.claimNumber()).isEqualTo("CLM-2026-000045");
        assertThat(response.status()).isEqualTo(ClaimStatus.FNOL);
        assertThat(response.reportedAt()).isEqualTo(NOW);

        ArgumentCaptor<ClaimEvent> event = ArgumentCaptor.forClass(ClaimEvent.class);
        verify(claimEventRepository).save(event.capture());
        assertThat(event.getValue().getFromStatus()).isNull();
        assertThat(event.getValue().getToStatus()).isEqualTo(ClaimStatus.FNOL);
        assertThat(event.getValue().getActor()).isEqualTo("agent.smith");
    }

    @Test
    void incidentTodayIsAllowed() {
        when(policyRepository.findById(10L)).thenReturn(Optional.of(TestFixtures.policy()));
        when(claimRepository.save(any(Claim.class))).thenAnswer(inv -> inv.getArgument(0));

        ClaimResponse response = service.fileClaim(request(LocalDate.of(2026, 6, 15)), "agent");

        assertThat(response.status()).isEqualTo(ClaimStatus.FNOL);
    }

    @Test
    void rejectsClaimOnLapsedPolicy() {
        Policy policy = TestFixtures.withStatus(TestFixtures.policy(), PolicyStatus.LAPSED);
        when(policyRepository.findById(10L)).thenReturn(Optional.of(policy));

        assertThatThrownBy(() -> service.fileClaim(request(LocalDate.of(2026, 6, 10)), "agent"))
                .isInstanceOf(BusinessRuleViolationException.class)
                .hasMessageContaining("LAPSED");
        verify(claimRepository, never()).save(any());
    }

    @Test
    void rejectsClaimOnCancelledPolicy() {
        Policy policy = TestFixtures.withStatus(TestFixtures.policy(), PolicyStatus.CANCELLED);
        when(policyRepository.findById(10L)).thenReturn(Optional.of(policy));

        assertThatThrownBy(() -> service.fileClaim(request(LocalDate.of(2026, 6, 10)), "agent"))
                .isInstanceOf(BusinessRuleViolationException.class);
    }

    @Test
    void rejectsIncidentInTheFuture() {
        when(policyRepository.findById(10L)).thenReturn(Optional.of(TestFixtures.policy()));

        assertThatThrownBy(() -> service.fileClaim(request(LocalDate.of(2026, 6, 16)), "agent"))
                .isInstanceOf(BusinessRuleViolationException.class)
                .hasMessageContaining("future");
    }

    @Test
    void rejectsIncidentBeforePolicyStart() {
        Policy policy = TestFixtures.policy(LocalDate.of(2026, 3, 1), LocalDate.of(2027, 2, 28), "10000", "500");
        when(policyRepository.findById(10L)).thenReturn(Optional.of(policy));

        assertThatThrownBy(() -> service.fileClaim(request(LocalDate.of(2026, 2, 28)), "agent"))
                .isInstanceOf(BusinessRuleViolationException.class)
                .hasMessageContaining("outside the policy period");
    }

    @Test
    void rejectsIncidentAfterPolicyEnd() {
        // Policy ended before "today", but it is still ACTIVE in our data.
        Policy policy = TestFixtures.policy(LocalDate.of(2025, 6, 1), LocalDate.of(2026, 5, 31), "10000", "500");
        when(policyRepository.findById(10L)).thenReturn(Optional.of(policy));

        assertThatThrownBy(() -> service.fileClaim(request(LocalDate.of(2026, 6, 1)), "agent"))
                .isInstanceOf(BusinessRuleViolationException.class)
                .hasMessageContaining("outside the policy period");
    }

    @Test
    void incidentOnPolicyStartDateIsAllowed() {
        Policy policy = TestFixtures.policy(LocalDate.of(2026, 6, 1), LocalDate.of(2027, 5, 31), "10000", "500");
        when(policyRepository.findById(10L)).thenReturn(Optional.of(policy));
        when(claimRepository.save(any(Claim.class))).thenAnswer(inv -> inv.getArgument(0));

        ClaimResponse response = service.fileClaim(request(LocalDate.of(2026, 6, 1)), "agent");

        assertThat(response.status()).isEqualTo(ClaimStatus.FNOL);
    }

    @Test
    void unknownPolicyIsNotFound() {
        when(policyRepository.findById(10L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.fileClaim(request(LocalDate.of(2026, 6, 10)), "agent"))
                .isInstanceOf(ResourceNotFoundException.class);
    }
}
