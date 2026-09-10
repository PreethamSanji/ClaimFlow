package com.claimflow.policy;

import static org.hamcrest.Matchers.hasItems;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import com.claimflow.common.BusinessRuleViolationException;

@WebMvcTest(PolicyController.class)
class PolicyControllerTest {

    @Autowired
    MockMvc mockMvc;

    @MockitoBean
    PolicyService policyService;

    private static PolicyResponse samplePolicy() {
        return new PolicyResponse(3L, "POL-2026-000003", 1L, PolicyType.AUTO,
                new BigDecimal("50000.00"), new BigDecimal("500.00"),
                LocalDate.of(2026, 1, 1), LocalDate.of(2026, 12, 31), PolicyStatus.ACTIVE);
    }

    @Test
    void createReturns201() throws Exception {
        when(policyService.create(any())).thenReturn(samplePolicy());

        mockMvc.perform(post("/api/v1/policies")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"customerId": 1, "type": "AUTO", "coverageLimit": 50000.00,
                                 "deductible": 500.00, "startDate": "2026-01-01", "endDate": "2026-12-31"}
                                """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.policyNumber").value("POL-2026-000003"))
                .andExpect(jsonPath("$.status").value("ACTIVE"));
    }

    @Test
    void createWithMissingAndNegativeFieldsReturns400() throws Exception {
        mockMvc.perform(post("/api/v1/policies")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"customerId": 1, "coverageLimit": -5, "deductible": 500.00,
                                 "startDate": "2026-01-01"}
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentType(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.errors[*].field", hasItems("type", "coverageLimit", "endDate")));
    }

    @Test
    void unknownPolicyTypeReturns400() throws Exception {
        mockMvc.perform(post("/api/v1/policies")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"customerId": 1, "type": "BOAT", "coverageLimit": 1000, "deductible": 0,
                                 "startDate": "2026-01-01", "endDate": "2026-12-31"}
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentType(MediaType.APPLICATION_PROBLEM_JSON));
    }

    @Test
    void businessRuleViolationReturns422() throws Exception {
        when(policyService.create(any()))
                .thenThrow(new BusinessRuleViolationException("endDate must be after startDate"));

        mockMvc.perform(post("/api/v1/policies")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"customerId": 1, "type": "HOME", "coverageLimit": 1000, "deductible": 0,
                                 "startDate": "2026-12-31", "endDate": "2026-01-01"}
                                """))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(content().contentType(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.status").value(422))
                .andExpect(jsonPath("$.detail").value("endDate must be after startDate"));
    }

    @Test
    void listByCustomerReturnsPolicies() throws Exception {
        when(policyService.findByCustomer(1L)).thenReturn(List.of(samplePolicy()));

        mockMvc.perform(get("/api/v1/policies").param("customerId", "1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].policyNumber").value("POL-2026-000003"));
    }

    @Test
    void listWithoutCustomerIdReturns400() throws Exception {
        mockMvc.perform(get("/api/v1/policies"))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentType(MediaType.APPLICATION_PROBLEM_JSON));
    }
}
