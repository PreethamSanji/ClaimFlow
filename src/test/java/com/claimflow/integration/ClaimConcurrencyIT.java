package com.claimflow.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.doAnswer;

import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

import com.claimflow.TestcontainersConfiguration;
import com.claimflow.claim.ClaimResponse;
import com.claimflow.claim.ClaimStateMachine;
import com.claimflow.claim.ClaimStatus;
import com.claimflow.policy.PolicyResponse;
import com.fasterxml.jackson.databind.JsonNode;

/**
 * Two requests change the same claim at the same moment. @Version must let one win
 * and turn the other into a 409.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(TestcontainersConfiguration.class)
class ClaimConcurrencyIT {

    @Autowired
    TestRestTemplate rest;

    // A real state machine we can pause, to force both requests to overlap.
    @MockitoSpyBean
    ClaimStateMachine stateMachine;

    @Test
    void concurrentTransitionsOnSameClaimOneWinsOtherGets409() throws Exception {
        ApiClient api = new ApiClient(rest);
        LocalDate today = LocalDate.now(ZoneOffset.UTC);
        long customerId = api.createCustomer();
        PolicyResponse policy = api.createPolicy(customerId, today.minusDays(60), today.plusDays(300),
                "10000.00", "500.00");
        ClaimResponse claim = api.fileClaim(policy.id(), today.minusDays(2), "1000.00");
        api.moveTo(claim.id(), ClaimStatus.UNDER_REVIEW, null);

        // Both requests load the claim (same version), then wait here for each other
        // before writing. So both writes are based on the same old version.
        CyclicBarrier bothLoaded = new CyclicBarrier(2);
        doAnswer(invocation -> {
            bothLoaded.await(10, TimeUnit.SECONDS);
            return invocation.callRealMethod();
        }).when(stateMachine).validate(ClaimStatus.UNDER_REVIEW, ClaimStatus.REJECTED);

        List<ResponseEntity<JsonNode>> responses;
        try (ExecutorService pool = Executors.newFixedThreadPool(2)) {
            Future<ResponseEntity<JsonNode>> first = pool.submit(() ->
                    api.transition(claim.id(), ClaimStatus.REJECTED, "Duplicate claim", null, "adjuster.a"));
            Future<ResponseEntity<JsonNode>> second = pool.submit(() ->
                    api.transition(claim.id(), ClaimStatus.REJECTED, "Not covered", null, "adjuster.b"));
            responses = List.of(first.get(30, TimeUnit.SECONDS), second.get(30, TimeUnit.SECONDS));
        }

        assertThat(responses).extracting(ResponseEntity::getStatusCode)
                .containsExactlyInAnyOrder(HttpStatus.OK, HttpStatus.CONFLICT);
        JsonNode conflict = responses.stream()
                .filter(response -> response.getStatusCode() == HttpStatus.CONFLICT)
                .findFirst().orElseThrow().getBody();
        assertThat(conflict.get("title").asText()).isEqualTo("Concurrent modification");

        // The loser's transaction rolled back, so only one REJECTED event exists.
        JsonNode events = api.events(claim.id());
        assertThat(events).hasSize(3);
        assertThat(events.get(2).get("toStatus").asText()).isEqualTo("REJECTED");
    }
}
