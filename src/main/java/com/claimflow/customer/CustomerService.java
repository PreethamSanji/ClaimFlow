package com.claimflow.customer;

import java.time.Clock;
import java.time.Instant;
import java.util.Locale;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.claimflow.common.DuplicateResourceException;
import com.claimflow.common.ResourceNotFoundException;

@Service
@Transactional(readOnly = true)
public class CustomerService {

    private final CustomerRepository customerRepository;
    private final Clock clock;

    // Constructor injection: Spring passes in the beans. No @Autowired needed with one constructor.
    public CustomerService(CustomerRepository customerRepository, Clock clock) {
        this.customerRepository = customerRepository;
        this.clock = clock;
    }

    @Transactional
    public CustomerResponse create(CreateCustomerRequest request) {
        String email = request.email().trim().toLowerCase(Locale.ROOT);
        if (customerRepository.existsByEmail(email)) {
            throw new DuplicateResourceException("A customer with email " + email + " already exists");
        }
        Customer customer = new Customer(request.fullName().trim(), email, Instant.now(clock));
        return CustomerResponse.from(customerRepository.save(customer));
    }

    public CustomerResponse get(Long id) {
        return customerRepository.findById(id)
                .map(CustomerResponse::from)
                .orElseThrow(() -> new ResourceNotFoundException("Customer", id));
    }
}
