package com.claimflow.claim;

import static org.hamcrest.Matchers.hasItems;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import com.claimflow.common.BusinessRuleViolationException;
import com.claimflow.common.PageResponse;
import com.claimflow.common.ResourceNotFoundException;

@WebMvcTest(ClaimController.class)
class ClaimControllerTest {

    @Autowired
    MockMvc mockMvc;

    @MockitoBean
    ClaimService claimService;

    static ClaimResponse sampleClaim(ClaimStatus status) {
        return new ClaimResponse(5L, "CLM-2026-000005", 10L, "POL-2026-000010", LocalDate.of(2026, 6, 1),
                Instant.parse("2026-06-02T10:00:00Z"), "Hail damage", new BigDecimal("2500.00"), null, status,
                List.of(), 0L);
    }

    @Test
    void fnolReturns201AndPassesActorHeader() throws Exception {
        when(claimService.fileClaim(any(), eq("agent.smith"))).thenReturn(sampleClaim(ClaimStatus.FNOL));

        mockMvc.perform(post("/api/v1/claims")
                        .header("X-Actor", "agent.smith")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"policyId": 10, "incidentDate": "2026-06-01",
                                 "description": "Hail damage", "claimedAmount": 2500.00}
                                """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.claimNumber").value("CLM-2026-000005"))
                .andExpect(jsonPath("$.status").value("FNOL"));
    }

    @Test
    void fnolWithoutActorHeaderUsesDefault() throws Exception {
        when(claimService.fileClaim(any(), eq("api-user"))).thenReturn(sampleClaim(ClaimStatus.FNOL));

        mockMvc.perform(post("/api/v1/claims")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"policyId": 10, "incidentDate": "2026-06-01",
                                 "description": "Hail damage", "claimedAmount": 2500.00}
                                """))
                .andExpect(status().isCreated());

        verify(claimService).fileClaim(any(), eq("api-user"));
    }

    @Test
    void fnolWithInvalidFieldsReturns400WithFieldErrors() throws Exception {
        mockMvc.perform(post("/api/v1/claims")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"incidentDate": "2026-06-01", "description": " ", "claimedAmount": 10.123}
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentType(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.status").value(400))
                .andExpect(jsonPath("$.title").value("Validation failed"))
                .andExpect(jsonPath("$.errors[*].field", hasItems("policyId", "description", "claimedAmount")));

        verifyNoInteractions(claimService);
    }

    @Test
    void fnolWithNegativeAmountReturns400() throws Exception {
        mockMvc.perform(post("/api/v1/claims")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"policyId": 10, "incidentDate": "2026-06-01",
                                 "description": "x", "claimedAmount": -1}
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].field").value("claimedAmount"));
    }

    @Test
    void fnolBusinessRuleFailureReturns422() throws Exception {
        when(claimService.fileClaim(any(), any()))
                .thenThrow(new BusinessRuleViolationException("incidentDate cannot be in the future"));

        mockMvc.perform(post("/api/v1/claims")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"policyId": 10, "incidentDate": "2030-01-01",
                                 "description": "Hail damage", "claimedAmount": 2500.00}
                                """))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(content().contentType(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.title").value("Business rule violated"))
                .andExpect(jsonPath("$.detail").value("incidentDate cannot be in the future"));
    }

    @Test
    void getUnknownClaimReturns404() throws Exception {
        when(claimService.get(404L)).thenThrow(new ResourceNotFoundException("Claim", 404L));

        mockMvc.perform(get("/api/v1/claims/404"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.detail").value("Claim with id 404 was not found"));
    }

    @Test
    void searchPassesFiltersAndReturnsPage() throws Exception {
        when(claimService.search(ClaimStatus.UNDER_REVIEW, 10L, 1, 5)).thenReturn(
                new PageResponse<>(List.of(sampleClaim(ClaimStatus.UNDER_REVIEW)), 1, 5, 6, 2));

        mockMvc.perform(get("/api/v1/claims")
                        .param("status", "UNDER_REVIEW")
                        .param("policyId", "10")
                        .param("page", "1")
                        .param("size", "5"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[0].status").value("UNDER_REVIEW"))
                .andExpect(jsonPath("$.page").value(1))
                .andExpect(jsonPath("$.totalElements").value(6))
                .andExpect(jsonPath("$.totalPages").value(2));
    }

    @Test
    void searchWithTooLargePageSizeReturns400() throws Exception {
        mockMvc.perform(get("/api/v1/claims").param("size", "500"))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentType(MediaType.APPLICATION_PROBLEM_JSON));

        verifyNoInteractions(claimService);
    }

    @Test
    void searchWithUnknownStatusReturns400() throws Exception {
        mockMvc.perform(get("/api/v1/claims").param("status", "LOST"))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentType(MediaType.APPLICATION_PROBLEM_JSON));
    }
}
