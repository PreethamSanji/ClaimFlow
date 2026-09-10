package com.claimflow.claim;

import java.time.Instant;

public record ClaimEventResponse(
        Long id,
        ClaimStatus fromStatus,
        ClaimStatus toStatus,
        String reason,
        String actor,
        Instant occurredAt) {

    public static ClaimEventResponse from(ClaimEvent event) {
        return new ClaimEventResponse(
                event.getId(),
                event.getFromStatus(),
                event.getToStatus(),
                event.getReason(),
                event.getActor(),
                event.getOccurredAt());
    }
}
