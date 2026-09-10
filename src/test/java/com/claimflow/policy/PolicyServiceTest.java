package com.claimflow.policy;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.claimflow.common.BusinessRuleViolationException;
import com.claimflow.common.ResourceNotFoundException;
import com.claimflow.customer.Customer;
import com.claimflow.customer.CustomerRepository;

@ExtendWith(MockitoExtension.class)
class PolicyServiceTest {

    @Mock
    PolicyRepository policyRepository;

    @Mock
    CustomerRepository customerRepository;

    PolicyService service;

    @BeforeEach
    void setUp() {
        Clock clock = Clock.fixed(Instant.parse("2026-03-15T10:00:00Z"), ZoneOffset.UTC);
        service = new PolicyService(policyRepository, customerRepository, clock);
    }

    private static CreatePolicyRequest request(String coverage, String deductible, LocalDate start, LocalDate end) {
        return new CreatePolicyRequest(1L, PolicyType.AUTO, new BigDecimal(coverage), new BigDecimal(deductible),
                start, end);
    }

    @Test
    void createGeneratesPolicyNumberAndStartsActive() {
        when(customerRepository.findById(1L))
                .thenReturn(Optional.of(new Customer("Asha", "asha@example.com", Instant.now())));
        when(policyRepository.nextPolicyNumberSequence()).thenReturn(123L);
        when(policyRepository.save(any(Policy.class))).thenAnswer(inv -> inv.getArgument(0));

        PolicyResponse response = service.create(
                request("50000.00", "500.00", LocalDate.of(2026, 1, 1), LocalDate.of(2026, 12, 31)));

        assertThat(response.policyNumber()).isEqualTo("POL-2026-000123");
        assertThat(response.status()).isEqualTo(PolicyStatus.ACTIVE);
    }

    @Test
    void createRejectsEndDateNotAfterStartDate() {
        when(customerRepository.findById(1L))
                .thenReturn(Optional.of(new Customer("Asha", "asha@example.com", Instant.now())));

        assertThatThrownBy(() -> service.create(
                request("50000.00", "500.00", LocalDate.of(2026, 5, 1), LocalDate.of(2026, 5, 1))))
                .isInstanceOf(BusinessRuleViolationException.class)
                .hasMessageContaining("endDate");
        verify(policyRepository, never()).save(any());
    }

    @Test
    void createRejectsDeductibleNotBelowCoverage() {
        when(customerRepository.findById(1L))
                .thenReturn(Optional.of(new Customer("Asha", "asha@example.com", Instant.now())));

        assertThatThrownBy(() -> service.create(
                request("1000.00", "1000.00", LocalDate.of(2026, 1, 1), LocalDate.of(2026, 12, 31))))
                .isInstanceOf(BusinessRuleViolationException.class)
                .hasMessageContaining("deductible");
    }

    @Test
    void createFailsForUnknownCustomer() {
        when(customerRepository.findById(1L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.create(
                request("1000.00", "0.00", LocalDate.of(2026, 1, 1), LocalDate.of(2026, 12, 31))))
                .isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    void findByCustomerFailsForUnknownCustomer() {
        when(customerRepository.existsById(42L)).thenReturn(false);

        assertThatThrownBy(() -> service.findByCustomer(42L))
                .isInstanceOf(ResourceNotFoundException.class);
    }
}
