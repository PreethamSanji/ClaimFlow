package com.claimflow.claim;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.Year;
import java.util.List;
import java.util.stream.Collectors;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.claimflow.common.BusinessRuleViolationException;
import com.claimflow.common.PageResponse;
import com.claimflow.common.ResourceNotFoundException;
import com.claimflow.fraud.FraudAssessmentService;
import com.claimflow.observability.ClaimMetrics;
import com.claimflow.policy.Policy;
import com.claimflow.policy.PolicyRepository;

@Service
@Transactional(readOnly = true)
public class ClaimService {

    public static final String FRAUD_ENGINE_ACTOR = "system:fraud-engine";

    private static final Logger log = LoggerFactory.getLogger(ClaimService.class);

    private final ClaimRepository claimRepository;
    private final ClaimEventRepository claimEventRepository;
    private final PolicyRepository policyRepository;
    private final ClaimStateMachine stateMachine;
    private final FraudAssessmentService fraudAssessmentService;
    private final ClaimMetrics metrics;
    private final Clock clock;

    public ClaimService(ClaimRepository claimRepository,
                        ClaimEventRepository claimEventRepository,
                        PolicyRepository policyRepository,
                        ClaimStateMachine stateMachine,
                        FraudAssessmentService fraudAssessmentService,
                        ClaimMetrics metrics,
                        Clock clock) {
        this.claimRepository = claimRepository;
        this.claimEventRepository = claimEventRepository;
        this.policyRepository = policyRepository;
        this.stateMachine = stateMachine;
        this.fraudAssessmentService = fraudAssessmentService;
        this.metrics = metrics;
        this.clock = clock;
    }

    /** FNOL = First Notice Of Loss: the customer reports a new claim. */
    @Transactional
    public ClaimResponse fileClaim(CreateClaimRequest request, String actor) {
        Policy policy = policyRepository.findById(request.policyId())
                .orElseThrow(() -> new ResourceNotFoundException("Policy", request.policyId()));
        checkFnolRules(policy, request.incidentDate());

        Instant now = Instant.now(clock);
        Claim claim = claimRepository.save(new Claim(nextClaimNumber(), policy, request.incidentDate(), now,
                request.description().trim(), request.claimedAmount()));
        claimEventRepository.save(new ClaimEvent(claim, null, ClaimStatus.FNOL, "First notice of loss", actor, now));
        metrics.claimCreated();

        try (MDC.MDCCloseable ignored = MDC.putCloseable("claimNumber", claim.getClaimNumber())) {
            log.info("Claim filed on policy {} for {}", policy.getPolicyNumber(), claim.getClaimedAmount());
        }
        return ClaimResponse.from(claim);
    }

    /**
     * Moves a claim to a new status. Steps: check the move is allowed, check approval
     * amounts, write the audit event, run fraud rules if it just entered review, save.
     */
    @Transactional
    public ClaimResponse transition(Long claimId, TransitionRequest request, String actor) {
        Claim claim = findClaim(claimId);
        try (MDC.MDCCloseable ignored = MDC.putCloseable("claimNumber", claim.getClaimNumber())) {
            ClaimStatus from = claim.getStatus();
            ClaimStatus to = request.toStatus();
            stateMachine.validate(from, to);

            if (to == ClaimStatus.APPROVED) {
                claim.approve(checkApprovedAmount(claim, request.approvedAmount()));
            } else if (request.approvedAmount() != null) {
                throw new BusinessRuleViolationException("approvedAmount is only allowed when moving to APPROVED");
            }

            recordTransition(claim, to, request.reason(), actor);

            // Fraud rules run once: when a new claim first enters review.
            // Coming back from investigation (FLAGGED -> UNDER_REVIEW) means a human cleared it.
            if (from == ClaimStatus.FNOL && to == ClaimStatus.UNDER_REVIEW) {
                runFraudAssessment(claim);
            }

            // Flush now so a @Version conflict fails here and becomes a 409.
            claimRepository.saveAndFlush(claim);
            return ClaimResponse.from(claim);
        }
    }

    public ClaimResponse get(Long id) {
        return ClaimResponse.from(findClaim(id));
    }

    public PageResponse<ClaimResponse> search(ClaimStatus status, Long policyId, int page, int size) {
        Pageable pageable = PageRequest.of(page, size, Sort.by(Sort.Direction.DESC, "id"));
        Page<Claim> claims;
        if (status != null && policyId != null) {
            claims = claimRepository.findByStatusAndPolicyId(status, policyId, pageable);
        } else if (status != null) {
            claims = claimRepository.findByStatus(status, pageable);
        } else if (policyId != null) {
            claims = claimRepository.findByPolicyId(policyId, pageable);
        } else {
            claims = claimRepository.findAll(pageable);
        }
        return PageResponse.from(claims.map(ClaimResponse::from));
    }

    public List<ClaimEventResponse> events(Long claimId) {
        if (!claimRepository.existsById(claimId)) {
            throw new ResourceNotFoundException("Claim", claimId);
        }
        return claimEventRepository.findByClaimIdOrderByOccurredAtAscIdAsc(claimId).stream()
                .map(ClaimEventResponse::from)
                .toList();
    }

    private void checkFnolRules(Policy policy, LocalDate incidentDate) {
        if (!policy.isActive()) {
            throw new BusinessRuleViolationException(
                    "Policy " + policy.getPolicyNumber() + " is " + policy.getStatus() + "; only ACTIVE policies accept claims");
        }
        if (incidentDate.isAfter(LocalDate.now(clock))) {
            throw new BusinessRuleViolationException("incidentDate cannot be in the future");
        }
        if (!policy.covers(incidentDate)) {
            throw new BusinessRuleViolationException("incidentDate " + incidentDate + " is outside the policy period "
                    + policy.getStartDate() + " to " + policy.getEndDate());
        }
    }

    private BigDecimal checkApprovedAmount(Claim claim, BigDecimal approvedAmount) {
        if (approvedAmount == null) {
            throw new BusinessRuleViolationException("approvedAmount is required when approving a claim");
        }
        Policy policy = claim.getPolicy();
        BigDecimal max = ApprovalLimits.maxApprovable(
                claim.getClaimedAmount(), policy.getDeductible(), policy.getCoverageLimit());
        if (approvedAmount.compareTo(max) > 0) {
            throw new BusinessRuleViolationException("approvedAmount " + approvedAmount
                    + " is more than the maximum payable " + max
                    + " (claimed amount minus deductible, capped at the coverage limit)");
        }
        return approvedAmount;
    }

    /** Any flag sends the claim straight to investigation, with the reasons in the audit trail. */
    private void runFraudAssessment(Claim claim) {
        List<FraudFlag> flags = fraudAssessmentService.assess(claim);
        if (flags.isEmpty()) {
            return;
        }
        claim.addFraudFlags(flags);
        stateMachine.validate(claim.getStatus(), ClaimStatus.FLAGGED_FOR_INVESTIGATION);
        String reasons = flags.stream().map(FraudFlag::toString).collect(Collectors.joining("; "));
        recordTransition(claim, ClaimStatus.FLAGGED_FOR_INVESTIGATION,
                "Auto-flagged by fraud rules: " + reasons, FRAUD_ENGINE_ACTOR);
    }

    /** Changes the status and writes one audit row. Caller must validate the move first. */
    private void recordTransition(Claim claim, ClaimStatus to, String reason, String actor) {
        ClaimStatus from = claim.getStatus();
        claim.changeStatus(to);
        claimEventRepository.save(new ClaimEvent(claim, from, to, reason, actor, Instant.now(clock)));
        metrics.transition(from, to);
        log.info("Claim moved from {} to {} by {}", from, to, actor);
    }

    private Claim findClaim(Long id) {
        return claimRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Claim", id));
    }

    // e.g. CLM-2026-000045
    private String nextClaimNumber() {
        long sequence = claimRepository.nextClaimNumberSequence();
        return "CLM-%d-%06d".formatted(Year.now(clock).getValue(), sequence);
    }
}
