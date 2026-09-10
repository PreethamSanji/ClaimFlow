package com.claimflow.fraud;

import java.time.Instant;

/**
 * Extra facts the rules need that are not on the claim itself.
 * Loaded once per assessment so rules stay simple and don't hit the DB.
 *
 * @param now                     time of the assessment
 * @param recentClaimsForCustomer claims by the same customer in the frequency window (includes this one)
 */
public record ClaimContext(Instant now, long recentClaimsForCustomer) {
}
