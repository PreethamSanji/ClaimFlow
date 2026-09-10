package com.claimflow.integration;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;

import com.claimflow.TestcontainersConfiguration;
import com.claimflow.claim.ClaimResponse;
import com.claimflow.claim.ClaimStatus;
import com.claimflow.policy.PolicyResponse;
import com.fasterxml.jackson.databind.JsonNode;

/**
 * Full stack: real HTTP -> controllers -> services -> JPA -> Postgres (in Docker via Testcontainers).
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(TestcontainersConfiguration.class)
class ClaimLifecycleIT {

    @Autowired
    TestRestTemplate rest;

    ApiClient api;
    LocalDate today;

    @BeforeEach
    void setUp() {
        api = new ApiClient(rest);
        today = LocalDate.now(ZoneOffset.UTC);
    }

    /** Policy started 60 days ago (so EarlyClaimRule won't fire): limit 10,000, deductible 500. */
    private PolicyResponse newPolicy() {
        long customerId = api.createCustomer();
        return api.createPolicy(customerId, today.minusDays(60), today.plusDays(300), "10000.00", "500.00");
    }

    @Test
    void fullLifecycleFnolReviewFlaggedApprovedPaid() {
        PolicyResponse policy = newPolicy();

        // 1. FNOL for more than the coverage limit -> will trip AmountExceedsCoverageRule.
        ClaimResponse claim = api.fileClaim(policy.id(), today.minusDays(2), "12000.00");
        assertThat(claim.status()).isEqualTo(ClaimStatus.FNOL);
        assertThat(claim.claimNumber()).matches("CLM-\\d{4}-\\d{6}");

        // 2. Into review -> fraud engine flags it straight away.
        ResponseEntity<JsonNode> review = api.transition(claim.id(), ClaimStatus.UNDER_REVIEW, "Assigned", null,
                "adjuster.kim");
        assertThat(review.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(review.getBody().get("status").asText()).isEqualTo("FLAGGED_FOR_INVESTIGATION");
        assertThat(review.getBody().get("fraudFlags").get(0).get("rule").asText())
                .isEqualTo("AMOUNT_EXCEEDS_COVERAGE");

        // 3. Investigator clears it -> back to review (fraud rules don't re-run).
        assertThat(api.moveTo(claim.id(), ClaimStatus.UNDER_REVIEW, null)).isEqualTo("UNDER_REVIEW");

        // 4. Approving above the max payable (min(10,000, 12,000 - 500) = 10,000) is a 422.
        ResponseEntity<JsonNode> tooMuch = api.transition(claim.id(), ClaimStatus.APPROVED, "Approve", "10000.01",
                "adjuster.kim");
        assertThat(tooMuch.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
        assertThat(tooMuch.getBody().get("title").asText()).isEqualTo("Business rule violated");

        // 5. Approve exactly the max, then pay.
        assertThat(api.moveTo(claim.id(), ClaimStatus.APPROVED, "10000.00")).isEqualTo("APPROVED");
        assertThat(api.moveTo(claim.id(), ClaimStatus.PAID, null)).isEqualTo("PAID");

        // 6. PAID is terminal -> 409.
        ResponseEntity<JsonNode> afterPaid = api.transition(claim.id(), ClaimStatus.REJECTED, "Too late", null,
                "adjuster.kim");
        assertThat(afterPaid.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(afterPaid.getBody().get("title").asText()).isEqualTo("Invalid claim transition");

        // 7. Audit trail has one row per status change, in order.
        JsonNode events = api.events(claim.id());
        List<String> statuses = new ArrayList<>();
        events.forEach(event -> statuses.add(event.get("toStatus").asText()));
        assertThat(statuses).containsExactly("FNOL", "UNDER_REVIEW", "FLAGGED_FOR_INVESTIGATION",
                "UNDER_REVIEW", "APPROVED", "PAID");
        assertThat(events.get(0).get("fromStatus").isNull()).isTrue();
        assertThat(events.get(2).get("actor").asText()).isEqualTo("system:fraud-engine");
        assertThat(events.get(2).get("reason").asText()).contains("AMOUNT_EXCEEDS_COVERAGE");

        // 8. Stored values survive a fresh read.
        JsonNode stored = api.get("/api/v1/claims/{id}", claim.id());
        assertThat(stored.get("status").asText()).isEqualTo("PAID");
        assertThat(stored.get("approvedAmount").decimalValue()).isEqualByComparingTo("10000.00");
    }

    @Test
    void cleanClaimStaysUnderReview() {
        PolicyResponse policy = newPolicy();
        ClaimResponse claim = api.fileClaim(policy.id(), today.minusDays(3), "1500.00");

        assertThat(api.moveTo(claim.id(), ClaimStatus.UNDER_REVIEW, null)).isEqualTo("UNDER_REVIEW");
    }

    @Test
    void thirdClaimInThirtyDaysIsFlaggedForFrequency() {
        PolicyResponse policy = newPolicy();
        api.fileClaim(policy.id(), today.minusDays(5), "100.00");
        api.fileClaim(policy.id(), today.minusDays(4), "100.00");
        ClaimResponse third = api.fileClaim(policy.id(), today.minusDays(3), "100.00");

        ResponseEntity<JsonNode> review = api.transition(third.id(), ClaimStatus.UNDER_REVIEW, "Assigned", null,
                "adjuster");

        assertThat(review.getBody().get("status").asText()).isEqualTo("FLAGGED_FOR_INVESTIGATION");
        assertThat(review.getBody().get("fraudFlags").get(0).get("rule").asText()).isEqualTo("HIGH_FREQUENCY");
    }

    @Test
    void fnolBusinessRulesReturn422() {
        PolicyResponse policy = newPolicy();

        ResponseEntity<JsonNode> future = api.fileClaimRaw(policy.id(), today.plusDays(1), "100.00");
        assertThat(future.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
        assertThat(future.getBody().get("detail").asText()).contains("future");

        ResponseEntity<JsonNode> beforeStart = api.fileClaimRaw(policy.id(), today.minusDays(61), "100.00");
        assertThat(beforeStart.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
        assertThat(beforeStart.getBody().get("detail").asText()).contains("outside the policy period");
    }

    @Test
    void searchFiltersByPolicyAndStatus() {
        PolicyResponse policy = newPolicy();
        ClaimResponse first = api.fileClaim(policy.id(), today.minusDays(3), "100.00");
        api.fileClaim(policy.id(), today.minusDays(2), "200.00");
        api.moveTo(first.id(), ClaimStatus.UNDER_REVIEW, null);

        JsonNode all = api.get("/api/v1/claims?policyId={p}", policy.id());
        assertThat(all.get("totalElements").asLong()).isEqualTo(2);

        JsonNode fnolOnly = api.get("/api/v1/claims?policyId={p}&status=FNOL", policy.id());
        assertThat(fnolOnly.get("totalElements").asLong()).isEqualTo(1);
        assertThat(fnolOnly.get("content").get(0).get("claimedAmount").decimalValue()).isEqualByComparingTo("200.00");

        JsonNode paged = api.get("/api/v1/claims?policyId={p}&page=0&size=1", policy.id());
        assertThat(paged.get("content")).hasSize(1);
        assertThat(paged.get("totalPages").asInt()).isEqualTo(2);
    }

    @Test
    void duplicateCustomerEmailReturns409() {
        String body = """
                {"fullName": "Same Person", "email": "same-%s@example.com"}
                """.formatted(System.nanoTime());
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        HttpEntity<String> request = new HttpEntity<>(body, headers);

        assertThat(rest.postForEntity("/api/v1/customers", request, JsonNode.class).getStatusCode())
                .isEqualTo(HttpStatus.CREATED);
        assertThat(rest.postForEntity("/api/v1/customers", request, JsonNode.class).getStatusCode())
                .isEqualTo(HttpStatus.CONFLICT);
    }
}
