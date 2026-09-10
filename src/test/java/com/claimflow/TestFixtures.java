package com.claimflow;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;

import org.springframework.test.util.ReflectionTestUtils;

import com.claimflow.claim.Claim;
import com.claimflow.claim.ClaimStatus;
import com.claimflow.customer.Customer;
import com.claimflow.policy.Policy;
import com.claimflow.policy.PolicyStatus;
import com.claimflow.policy.PolicyType;

/**
 * Builds entities for unit tests. Ids and statuses are normally set by the DB
 * or the service, so we set them with reflection here.
 */
public final class TestFixtures {

    private TestFixtures() {
    }

    public static Customer customer(long id) {
        Customer customer = new Customer("Test Customer", "customer" + id + "@example.com",
                Instant.parse("2026-01-01T00:00:00Z"));
        ReflectionTestUtils.setField(customer, "id", id);
        return customer;
    }

    /** Active AUTO policy, 2026-01-01 to 2026-12-31, coverage 10,000, deductible 500. */
    public static Policy policy() {
        return policy(LocalDate.of(2026, 1, 1), LocalDate.of(2026, 12, 31), "10000.00", "500.00");
    }

    public static Policy policy(LocalDate start, LocalDate end, String coverageLimit, String deductible) {
        Policy policy = new Policy("POL-2026-000001", customer(1L), PolicyType.AUTO,
                new BigDecimal(coverageLimit), new BigDecimal(deductible), start, end);
        ReflectionTestUtils.setField(policy, "id", 10L);
        return policy;
    }

    public static Policy withStatus(Policy policy, PolicyStatus status) {
        ReflectionTestUtils.setField(policy, "status", status);
        return policy;
    }

    public static Claim claim(Policy policy, LocalDate incidentDate, Instant reportedAt, String claimedAmount) {
        Claim claim = new Claim("CLM-2026-000001", policy, incidentDate, reportedAt, "Test claim",
                new BigDecimal(claimedAmount));
        ReflectionTestUtils.setField(claim, "id", 100L);
        ReflectionTestUtils.setField(claim, "version", 0L);
        return claim;
    }

    public static Claim claimInStatus(Policy policy, String claimedAmount, ClaimStatus status) {
        Claim claim = claim(policy, LocalDate.of(2026, 3, 1), Instant.parse("2026-03-02T10:00:00Z"), claimedAmount);
        ReflectionTestUtils.setField(claim, "status", status);
        return claim;
    }
}
