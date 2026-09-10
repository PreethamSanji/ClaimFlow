package com.claimflow.integration;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;
import java.time.ZoneOffset;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.actuate.observability.AutoConfigureObservability;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import com.claimflow.TestcontainersConfiguration;
import com.claimflow.claim.ClaimResponse;
import com.claimflow.claim.ClaimStatus;
import com.claimflow.policy.PolicyResponse;
import com.fasterxml.jackson.databind.JsonNode;

/** Actuator endpoints Kubernetes and Prometheus depend on, plus the API docs. */
// Spring Boot tests turn metrics exporters off by default; this turns Prometheus back on.
@AutoConfigureObservability
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(TestcontainersConfiguration.class)
class ObservabilityIT {

    @Autowired
    TestRestTemplate rest;

    @Test
    void livenessAndReadinessProbesAreUp() {
        ResponseEntity<JsonNode> liveness = rest.getForEntity("/actuator/health/liveness", JsonNode.class);
        ResponseEntity<JsonNode> readiness = rest.getForEntity("/actuator/health/readiness", JsonNode.class);

        assertThat(liveness.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(liveness.getBody().get("status").asText()).isEqualTo("UP");
        assertThat(readiness.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(readiness.getBody().get("status").asText()).isEqualTo("UP");
    }

    @Test
    void prometheusEndpointExposesCustomClaimMetrics() {
        // Make some business activity: a claim that gets auto-flagged.
        ApiClient api = new ApiClient(rest);
        LocalDate today = LocalDate.now(ZoneOffset.UTC);
        PolicyResponse policy = api.createPolicy(api.createCustomer(), today.minusDays(60), today.plusDays(300),
                "1000.00", "100.00");
        ClaimResponse claim = api.fileClaim(policy.id(), today.minusDays(1), "5000.00");
        api.moveTo(claim.id(), ClaimStatus.UNDER_REVIEW, null);

        String metrics = rest.getForObject("/actuator/prometheus", String.class);

        assertThat(metrics)
                .contains("claims_filed_total")
                .contains("claims_transitions_total{")
                .contains("from=\"FNOL\"")
                .contains("to=\"FLAGGED_FOR_INVESTIGATION\"")
                .contains("claims_fraud_flags_total{")
                .contains("rule=\"AMOUNT_EXCEEDS_COVERAGE\"")
                .contains("claims_fraud_assessment_seconds_count");
    }

    @Test
    void infoEndpointShowsAppName() {
        String info = rest.getForObject("/actuator/info", String.class);

        assertThat(info).contains("claimflow");
    }

    @Test
    void openApiDocsListClaimEndpoints() {
        ResponseEntity<String> docs = rest.getForEntity("/v3/api-docs", String.class);

        assertThat(docs.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(docs.getBody()).contains("/api/v1/claims/{id}/transitions");
    }
}
