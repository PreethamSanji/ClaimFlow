package com.claimflow.policy;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

public interface PolicyRepository extends JpaRepository<Policy, Long> {

    List<Policy> findByCustomerIdOrderByIdAsc(Long customerId);

    @Query(value = "SELECT nextval('policy_number_seq')", nativeQuery = true)
    long nextPolicyNumberSequence();
}
