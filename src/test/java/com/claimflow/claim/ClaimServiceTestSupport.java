package com.claimflow.claim;

import java.time.Clock;

import com.claimflow.policy.PolicyRepository;

/** One place to build ClaimService for unit tests, so constructor changes touch one file. */
final class ClaimServiceTestSupport {

    private ClaimServiceTestSupport() {
    }

    static ClaimService newService(ClaimRepository claimRepository,
                                   ClaimEventRepository claimEventRepository,
                                   PolicyRepository policyRepository,
                                   Clock clock) {
        return new ClaimService(claimRepository, claimEventRepository, policyRepository, clock);
    }
}
