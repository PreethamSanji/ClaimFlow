package com.claimflow.customer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.claimflow.common.DuplicateResourceException;

@ExtendWith(MockitoExtension.class)
class CustomerServiceTest {

    private static final Instant NOW = Instant.parse("2026-03-15T10:00:00Z");

    @Mock
    CustomerRepository customerRepository;

    CustomerService service;

    @BeforeEach
    void setUp() {
        service = new CustomerService(customerRepository, Clock.fixed(NOW, ZoneOffset.UTC));
    }

    @Test
    void createNormalizesEmailAndSetsCreatedAt() {
        when(customerRepository.existsByEmail("asha@example.com")).thenReturn(false);
        when(customerRepository.save(any(Customer.class))).thenAnswer(inv -> inv.getArgument(0));

        CustomerResponse response = service.create(new CreateCustomerRequest(" Asha Rao ", " Asha@Example.COM "));

        assertThat(response.fullName()).isEqualTo("Asha Rao");
        assertThat(response.email()).isEqualTo("asha@example.com");
        assertThat(response.createdAt()).isEqualTo(NOW);
    }

    @Test
    void createRejectsDuplicateEmail() {
        when(customerRepository.existsByEmail("asha@example.com")).thenReturn(true);

        assertThatThrownBy(() -> service.create(new CreateCustomerRequest("Asha", "asha@example.com")))
                .isInstanceOf(DuplicateResourceException.class);
        verify(customerRepository, never()).save(any());
    }
}
