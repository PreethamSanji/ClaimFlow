package com.claimflow.claim;

import static com.claimflow.claim.ClaimStatus.APPROVED;
import static com.claimflow.claim.ClaimStatus.FLAGGED_FOR_INVESTIGATION;
import static com.claimflow.claim.ClaimStatus.FNOL;
import static com.claimflow.claim.ClaimStatus.PAID;
import static com.claimflow.claim.ClaimStatus.REJECTED;
import static com.claimflow.claim.ClaimStatus.UNDER_REVIEW;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.params.provider.Arguments.arguments;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.MethodSource;

class ClaimStateMachineTest {

    // The spec's lifecycle, written out again on purpose so the test doesn't just copy the code.
    private static final Set<List<ClaimStatus>> VALID = Set.of(
            List.of(FNOL, UNDER_REVIEW),
            List.of(UNDER_REVIEW, APPROVED),
            List.of(UNDER_REVIEW, REJECTED),
            List.of(UNDER_REVIEW, FLAGGED_FOR_INVESTIGATION),
            List.of(FLAGGED_FOR_INVESTIGATION, UNDER_REVIEW),
            List.of(FLAGGED_FOR_INVESTIGATION, REJECTED),
            List.of(APPROVED, PAID));

    private final ClaimStateMachine stateMachine = new ClaimStateMachine();

    static Stream<Arguments> validTransitions() {
        return VALID.stream().map(pair -> arguments(pair.get(0), pair.get(1)));
    }

    /** Every (from, to) pair not in VALID, including "same status" moves. 36 - 7 = 29 pairs. */
    static Stream<Arguments> invalidTransitions() {
        List<Arguments> invalid = new ArrayList<>();
        for (ClaimStatus from : ClaimStatus.values()) {
            for (ClaimStatus to : ClaimStatus.values()) {
                if (!VALID.contains(List.of(from, to))) {
                    invalid.add(arguments(from, to));
                }
            }
        }
        return invalid.stream();
    }

    @ParameterizedTest(name = "{0} -> {1} is allowed")
    @MethodSource("validTransitions")
    void allowsValidTransitions(ClaimStatus from, ClaimStatus to) {
        assertThat(stateMachine.canTransition(from, to)).isTrue();
        assertThatCode(() -> stateMachine.validate(from, to)).doesNotThrowAnyException();
    }

    @ParameterizedTest(name = "{0} -> {1} is rejected")
    @MethodSource("invalidTransitions")
    void rejectsInvalidTransitions(ClaimStatus from, ClaimStatus to) {
        assertThat(stateMachine.canTransition(from, to)).isFalse();
        assertThatThrownBy(() -> stateMachine.validate(from, to))
                .isInstanceOf(InvalidClaimTransitionException.class)
                .hasMessage("Cannot move a claim from " + from + " to " + to);
    }

    @Test
    void invalidListCoversAllOtherPairs() {
        assertThat(invalidTransitions().count()).isEqualTo(29);
    }

    @ParameterizedTest
    @EnumSource(value = ClaimStatus.class, names = {"PAID", "REJECTED"})
    void paidAndRejectedAreTerminal(ClaimStatus status) {
        assertThat(stateMachine.isTerminal(status)).isTrue();
        assertThat(stateMachine.allowedTargets(status)).isEmpty();
    }

    @ParameterizedTest
    @EnumSource(value = ClaimStatus.class, names = {"PAID", "REJECTED"}, mode = EnumSource.Mode.EXCLUDE)
    void otherStatusesAreNotTerminal(ClaimStatus status) {
        assertThat(stateMachine.isTerminal(status)).isFalse();
    }
}
