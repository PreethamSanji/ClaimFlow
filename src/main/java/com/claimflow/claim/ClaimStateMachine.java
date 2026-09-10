package com.claimflow.claim;

import static com.claimflow.claim.ClaimStatus.APPROVED;
import static com.claimflow.claim.ClaimStatus.FLAGGED_FOR_INVESTIGATION;
import static com.claimflow.claim.ClaimStatus.FNOL;
import static com.claimflow.claim.ClaimStatus.PAID;
import static com.claimflow.claim.ClaimStatus.REJECTED;
import static com.claimflow.claim.ClaimStatus.UNDER_REVIEW;

import java.util.Map;
import java.util.Set;

import org.springframework.stereotype.Component;

/**
 * All allowed claim status moves in one table.
 *
 * FNOL -> UNDER_REVIEW -> APPROVED -> PAID
 *                      -> REJECTED
 *                      -> FLAGGED_FOR_INVESTIGATION -> UNDER_REVIEW | REJECTED
 */
@Component
public class ClaimStateMachine {

    private static final Map<ClaimStatus, Set<ClaimStatus>> ALLOWED = Map.of(
            FNOL, Set.of(UNDER_REVIEW),
            UNDER_REVIEW, Set.of(APPROVED, REJECTED, FLAGGED_FOR_INVESTIGATION),
            FLAGGED_FOR_INVESTIGATION, Set.of(UNDER_REVIEW, REJECTED),
            APPROVED, Set.of(PAID),
            REJECTED, Set.of(),
            PAID, Set.of());

    public boolean canTransition(ClaimStatus from, ClaimStatus to) {
        return ALLOWED.get(from).contains(to);
    }

    /** Throws InvalidClaimTransitionException if the move is not allowed. */
    public void validate(ClaimStatus from, ClaimStatus to) {
        if (!canTransition(from, to)) {
            throw new InvalidClaimTransitionException(from, to);
        }
    }

    public Set<ClaimStatus> allowedTargets(ClaimStatus from) {
        return ALLOWED.get(from);
    }

    public boolean isTerminal(ClaimStatus status) {
        return ALLOWED.get(status).isEmpty();
    }
}
