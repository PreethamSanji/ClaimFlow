package com.claimflow.policy;

import java.time.Clock;
import java.time.Year;
import java.util.List;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.claimflow.common.BusinessRuleViolationException;
import com.claimflow.common.ResourceNotFoundException;
import com.claimflow.customer.Customer;
import com.claimflow.customer.CustomerRepository;

@Service
@Transactional(readOnly = true)
public class PolicyService {

    private final PolicyRepository policyRepository;
    private final CustomerRepository customerRepository;
    private final Clock clock;

    public PolicyService(PolicyRepository policyRepository, CustomerRepository customerRepository, Clock clock) {
        this.policyRepository = policyRepository;
        this.customerRepository = customerRepository;
        this.clock = clock;
    }

    @Transactional
    public PolicyResponse create(CreatePolicyRequest request) {
        Customer customer = customerRepository.findById(request.customerId())
                .orElseThrow(() -> new ResourceNotFoundException("Customer", request.customerId()));

        if (!request.endDate().isAfter(request.startDate())) {
            throw new BusinessRuleViolationException("endDate must be after startDate");
        }
        if (request.deductible().compareTo(request.coverageLimit()) >= 0) {
            throw new BusinessRuleViolationException("deductible must be less than coverageLimit");
        }

        Policy policy = new Policy(nextPolicyNumber(), customer, request.type(), request.coverageLimit(),
                request.deductible(), request.startDate(), request.endDate());
        return PolicyResponse.from(policyRepository.save(policy));
    }

    public PolicyResponse get(Long id) {
        return policyRepository.findById(id)
                .map(PolicyResponse::from)
                .orElseThrow(() -> new ResourceNotFoundException("Policy", id));
    }

    public List<PolicyResponse> findByCustomer(Long customerId) {
        if (!customerRepository.existsById(customerId)) {
            throw new ResourceNotFoundException("Customer", customerId);
        }
        return policyRepository.findByCustomerIdOrderByIdAsc(customerId).stream()
                .map(PolicyResponse::from)
                .toList();
    }

    // e.g. POL-2026-000123
    private String nextPolicyNumber() {
        long sequence = policyRepository.nextPolicyNumberSequence();
        return "POL-%d-%06d".formatted(Year.now(clock).getValue(), sequence);
    }
}
