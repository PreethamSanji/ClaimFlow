package com.claimflow.claim;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.Year;

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
import com.claimflow.policy.Policy;
import com.claimflow.policy.PolicyRepository;

@Service
@Transactional(readOnly = true)
public class ClaimService {

    private static final Logger log = LoggerFactory.getLogger(ClaimService.class);

    private final ClaimRepository claimRepository;
    private final ClaimEventRepository claimEventRepository;
    private final PolicyRepository policyRepository;
    private final Clock clock;

    public ClaimService(ClaimRepository claimRepository,
                        ClaimEventRepository claimEventRepository,
                        PolicyRepository policyRepository,
                        Clock clock) {
        this.claimRepository = claimRepository;
        this.claimEventRepository = claimEventRepository;
        this.policyRepository = policyRepository;
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

        try (MDC.MDCCloseable ignored = MDC.putCloseable("claimNumber", claim.getClaimNumber())) {
            log.info("Claim filed on policy {} for {}", policy.getPolicyNumber(), claim.getClaimedAmount());
        }
        return ClaimResponse.from(claim);
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
