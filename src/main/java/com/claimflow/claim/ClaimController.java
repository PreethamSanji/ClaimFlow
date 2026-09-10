package com.claimflow.claim;

import java.net.URI;
import java.util.List;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.support.ServletUriComponentsBuilder;

import com.claimflow.common.PageResponse;

import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Size;

@RestController
@RequestMapping("/api/v1/claims")
@Tag(name = "Claims")
public class ClaimController {

    // No auth in this project, so callers say who they are with this header (for the audit trail).
    static final String ACTOR_HEADER = "X-Actor";
    static final String DEFAULT_ACTOR = "api-user";

    private final ClaimService claimService;

    public ClaimController(ClaimService claimService) {
        this.claimService = claimService;
    }

    @PostMapping
    public ResponseEntity<ClaimResponse> fileClaim(
            @Valid @RequestBody CreateClaimRequest request,
            @RequestHeader(value = ACTOR_HEADER, defaultValue = DEFAULT_ACTOR) @Size(max = 100) String actor) {
        ClaimResponse created = claimService.fileClaim(request, actor);
        URI location = ServletUriComponentsBuilder.fromCurrentRequest()
                .path("/{id}").buildAndExpand(created.id()).toUri();
        return ResponseEntity.created(location).body(created);
    }

    @GetMapping("/{id}")
    public ClaimResponse get(@PathVariable Long id) {
        return claimService.get(id);
    }

    @PostMapping("/{id}/transitions")
    public ClaimResponse transition(
            @PathVariable Long id,
            @Valid @RequestBody TransitionRequest request,
            @RequestHeader(value = ACTOR_HEADER, defaultValue = DEFAULT_ACTOR) @Size(max = 100) String actor) {
        return claimService.transition(id, request, actor);
    }

    @GetMapping("/{id}/events")
    public List<ClaimEventResponse> events(@PathVariable Long id) {
        return claimService.events(id);
    }

    @GetMapping
    public PageResponse<ClaimResponse> search(
            @RequestParam(required = false) ClaimStatus status,
            @RequestParam(required = false) Long policyId,
            @RequestParam(defaultValue = "0") @Min(0) int page,
            @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size) {
        return claimService.search(status, policyId, page, size);
    }
}
