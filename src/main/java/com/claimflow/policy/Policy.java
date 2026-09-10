package com.claimflow.policy;

import java.math.BigDecimal;
import java.time.LocalDate;

import com.claimflow.customer.Customer;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;

@Entity
@Table(name = "policy")
public class Policy {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "policy_number", nullable = false, unique = true, updatable = false, length = 30)
    private String policyNumber;

    // LAZY: only load the customer when we actually use it.
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "customer_id", nullable = false)
    private Customer customer;

    @Enumerated(EnumType.STRING)
    @Column(name = "type", nullable = false, length = 20)
    private PolicyType type;

    @Column(name = "coverage_limit", nullable = false, precision = 15, scale = 2)
    private BigDecimal coverageLimit;

    @Column(name = "deductible", nullable = false, precision = 15, scale = 2)
    private BigDecimal deductible;

    @Column(name = "start_date", nullable = false)
    private LocalDate startDate;

    @Column(name = "end_date", nullable = false)
    private LocalDate endDate;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    private PolicyStatus status;

    protected Policy() {
    }

    public Policy(String policyNumber, Customer customer, PolicyType type, BigDecimal coverageLimit,
                  BigDecimal deductible, LocalDate startDate, LocalDate endDate) {
        this.policyNumber = policyNumber;
        this.customer = customer;
        this.type = type;
        this.coverageLimit = coverageLimit;
        this.deductible = deductible;
        this.startDate = startDate;
        this.endDate = endDate;
        this.status = PolicyStatus.ACTIVE;
    }

    public boolean isActive() {
        return status == PolicyStatus.ACTIVE;
    }

    /** True if the date falls inside the policy period (both ends included). */
    public boolean covers(LocalDate date) {
        return !date.isBefore(startDate) && !date.isAfter(endDate);
    }

    public Long getId() {
        return id;
    }

    public String getPolicyNumber() {
        return policyNumber;
    }

    public Customer getCustomer() {
        return customer;
    }

    public PolicyType getType() {
        return type;
    }

    public BigDecimal getCoverageLimit() {
        return coverageLimit;
    }

    public BigDecimal getDeductible() {
        return deductible;
    }

    public LocalDate getStartDate() {
        return startDate;
    }

    public LocalDate getEndDate() {
        return endDate;
    }

    public PolicyStatus getStatus() {
        return status;
    }
}
