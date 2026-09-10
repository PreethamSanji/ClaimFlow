package com.claimflow.integration;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;

import com.claimflow.claim.ClaimResponse;
import com.claimflow.claim.ClaimStatus;
import com.claimflow.claim.CreateClaimRequest;
import com.claimflow.customer.CreateCustomerRequest;
import com.claimflow.customer.CustomerResponse;
import com.claimflow.policy.CreatePolicyRequest;
import com.claimflow.policy.PolicyResponse;
import com.claimflow.policy.PolicyType;
import com.fasterxml.jackson.databind.JsonNode;

/** Small helper so integration tests read like the business flow. Talks real HTTP. */
class ApiClient {

    private final TestRestTemplate rest;

    ApiClient(TestRestTemplate rest) {
        this.rest = rest;
    }

    /** New customer with a unique email, so tests don't clash in the shared database. */
    long createCustomer() {
        ResponseEntity<CustomerResponse> response = rest.postForEntity("/api/v1/customers",
                new CreateCustomerRequest("Test Customer", "it-" + UUID.randomUUID() + "@example.com"),
                CustomerResponse.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        return response.getBody().id();
    }

    PolicyResponse createPolicy(long customerId, LocalDate start, LocalDate end, String coverage, String deductible) {
        ResponseEntity<PolicyResponse> response = rest.postForEntity("/api/v1/policies",
                new CreatePolicyRequest(customerId, PolicyType.HOME, new BigDecimal(coverage),
                        new BigDecimal(deductible), start, end),
                PolicyResponse.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        return response.getBody();
    }

    ResponseEntity<JsonNode> fileClaimRaw(long policyId, LocalDate incidentDate, String amount) {
        return rest.postForEntity("/api/v1/claims",
                new CreateClaimRequest(policyId, incidentDate, "Integration test claim", new BigDecimal(amount)),
                JsonNode.class);
    }

    ClaimResponse fileClaim(long policyId, LocalDate incidentDate, String amount) {
        ResponseEntity<ClaimResponse> response = rest.postForEntity("/api/v1/claims",
                new CreateClaimRequest(policyId, incidentDate, "Integration test claim", new BigDecimal(amount)),
                ClaimResponse.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        return response.getBody();
    }

    /** Returns raw JSON so callers can check both success bodies and ProblemDetail errors. */
    ResponseEntity<JsonNode> transition(long claimId, ClaimStatus to, String reason, String approvedAmount,
                                        String actor) {
        Map<String, Object> body = new HashMap<>();
        body.put("toStatus", to);
        body.put("reason", reason);
        if (approvedAmount != null) {
            body.put("approvedAmount", new BigDecimal(approvedAmount));
        }
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.set("X-Actor", actor);
        return rest.exchange("/api/v1/claims/{id}/transitions", HttpMethod.POST, new HttpEntity<>(body, headers),
                JsonNode.class, claimId);
    }

    /** Transition that must succeed. Returns the new status. */
    String moveTo(long claimId, ClaimStatus to, String approvedAmount) {
        ResponseEntity<JsonNode> response = transition(claimId, to, "moving to " + to, approvedAmount, "it-user");
        assertThat(response.getStatusCode()).as(String.valueOf(response.getBody())).isEqualTo(HttpStatus.OK);
        return response.getBody().get("status").asText();
    }

    JsonNode events(long claimId) {
        ResponseEntity<JsonNode> response = rest.getForEntity("/api/v1/claims/{id}/events", JsonNode.class, claimId);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        return response.getBody();
    }

    JsonNode get(String url, Object... uriVariables) {
        ResponseEntity<JsonNode> response = rest.getForEntity(url, JsonNode.class, uriVariables);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        return response.getBody();
    }
}
