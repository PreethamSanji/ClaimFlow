package com.claimflow.claim;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import com.claimflow.policy.Policy;

import jakarta.persistence.CollectionTable;
import jakarta.persistence.Column;
import jakarta.persistence.ElementCollection;
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
import jakarta.persistence.Version;

@Entity
@Table(name = "claim")
public class Claim {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "claim_number", nullable = false, unique = true, updatable = false, length = 30)
    private String claimNumber;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "policy_id", nullable = false, updatable = false)
    private Policy policy;

    @Column(name = "incident_date", nullable = false)
    private LocalDate incidentDate;

    @Column(name = "reported_at", nullable = false, updatable = false)
    private Instant reportedAt;

    @Column(name = "description", nullable = false, length = 2000)
    private String description;

    @Column(name = "claimed_amount", nullable = false, precision = 15, scale = 2)
    private BigDecimal claimedAmount;

    @Column(name = "approved_amount", precision = 15, scale = 2)
    private BigDecimal approvedAmount;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 40)
    private ClaimStatus status;

    @ElementCollection
    @CollectionTable(name = "claim_fraud_flag", joinColumns = @JoinColumn(name = "claim_id"))
    private List<FraudFlag> fraudFlags = new ArrayList<>();

    // Optimistic locking: Hibernate adds "WHERE version = ?" to every update.
    @Version
    @Column(name = "version", nullable = false)
    private Long version;

    protected Claim() {
    }

    public Claim(String claimNumber, Policy policy, LocalDate incidentDate, Instant reportedAt,
                 String description, BigDecimal claimedAmount) {
        this.claimNumber = claimNumber;
        this.policy = policy;
        this.incidentDate = incidentDate;
        this.reportedAt = reportedAt;
        this.description = description;
        this.claimedAmount = claimedAmount;
        this.status = ClaimStatus.FNOL;
    }

    /** Only call after ClaimStateMachine has approved the move. */
    void changeStatus(ClaimStatus newStatus) {
        this.status = newStatus;
    }

    void approve(BigDecimal amount) {
        this.approvedAmount = amount;
    }

    void addFraudFlags(List<FraudFlag> flags) {
        this.fraudFlags.addAll(flags);
    }

    public Long getId() {
        return id;
    }

    public String getClaimNumber() {
        return claimNumber;
    }

    public Policy getPolicy() {
        return policy;
    }

    public LocalDate getIncidentDate() {
        return incidentDate;
    }

    public Instant getReportedAt() {
        return reportedAt;
    }

    public String getDescription() {
        return description;
    }

    public BigDecimal getClaimedAmount() {
        return claimedAmount;
    }

    public BigDecimal getApprovedAmount() {
        return approvedAmount;
    }

    public ClaimStatus getStatus() {
        return status;
    }

    public List<FraudFlag> getFraudFlags() {
        return Collections.unmodifiableList(fraudFlags);
    }

    public Long getVersion() {
        return version;
    }
}
