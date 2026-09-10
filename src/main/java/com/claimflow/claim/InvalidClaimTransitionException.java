package com.claimflow.claim;

/** A status change the state machine does not allow. Mapped to HTTP 409. */
public class InvalidClaimTransitionException extends RuntimeException {

    private final ClaimStatus fromStatus;
    private final ClaimStatus toStatus;

    public InvalidClaimTransitionException(ClaimStatus fromStatus, ClaimStatus toStatus) {
        super("Cannot move a claim from " + fromStatus + " to " + toStatus);
        this.fromStatus = fromStatus;
        this.toStatus = toStatus;
    }

    public ClaimStatus getFromStatus() {
        return fromStatus;
    }

    public ClaimStatus getToStatus() {
        return toStatus;
    }
}
