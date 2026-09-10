package com.claimflow.claim;

import java.time.Instant;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface ClaimRepository extends JpaRepository<Claim, Long> {

    Page<Claim> findByStatus(ClaimStatus status, Pageable pageable);

    Page<Claim> findByPolicyId(Long policyId, Pageable pageable);

    Page<Claim> findByStatusAndPolicyId(ClaimStatus status, Long policyId, Pageable pageable);

    /** Claims filed by a customer (across all their policies) since a point in time. */
    @Query("""
            SELECT COUNT(c) FROM Claim c
            WHERE c.policy.customer.id = :customerId
              AND c.reportedAt >= :since
            """)
    long countByCustomerSince(@Param("customerId") Long customerId, @Param("since") Instant since);

    @Query(value = "SELECT nextval('claim_number_seq')", nativeQuery = true)
    long nextClaimNumberSequence();
}
