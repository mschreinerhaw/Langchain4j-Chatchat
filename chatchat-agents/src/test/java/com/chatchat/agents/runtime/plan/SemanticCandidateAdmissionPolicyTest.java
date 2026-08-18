package com.chatchat.agents.runtime.plan;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Locale;
import java.util.Random;
import java.util.Set;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

class SemanticCandidateAdmissionPolicyTest {

    private final SemanticCandidateAdmissionPolicy policy = new SemanticCandidateAdmissionPolicy();

    @Test
    void admitsOnlyAuthorizedExplicitSelections() {
        SemanticCandidateAdmissionPolicy.Decision decision = policy.decide(
            List.of("candidate-a", "candidate-b"),
            List.of("invented", "candidate-b"),
            List.of("candidate-a"),
            false
        );

        assertThat(decision.decided()).isTrue();
        assertThat(decision.selectedIds()).containsExactly("candidate-b");
        assertThat(decision.authority()).isEqualTo("runtime_evidence_model_review");
    }

    @Test
    void uniqueCandidateIsDeterministicOnlyAfterReviewerRan() {
        assertThat(policy.decide(List.of("only"), List.of(), List.of(), false).selectedIds())
            .containsExactly("only");
        assertThat(policy.decide(List.of("only"), List.of(), List.of(), true).decided())
            .isFalse();
    }

    @Test
    void ambiguousCandidatesNeverBecomeJavaSemanticChoice() {
        assertThat(policy.decide(
            List.of("a", "b", "c"), List.of(), List.of("c"), false).decided())
            .isFalse();
    }

    @Test
    void rejectionCanDecideOnlyWhenAtMostOneCandidateSurvives() {
        SemanticCandidateAdmissionPolicy.Decision one = policy.decide(
            List.of("a", "b"), List.of(), List.of("b"), false);
        SemanticCandidateAdmissionPolicy.Decision none = policy.decide(
            List.of("a", "b"), List.of(), List.of("a", "b"), false);

        assertThat(one.selectedIds()).containsExactly("a");
        assertThat(none.decided()).isTrue();
        assertThat(none.selectedIds()).isEmpty();
    }

    @Test
    void neverAdmitsUnauthorizedOrRejectedIdsUnderRandomizedLoad() {
        Random random = new Random(20260818L);
        for (int iteration = 0; iteration < 10_000; iteration++) {
            int candidateCount = 1 + random.nextInt(20);
            List<String> candidates = java.util.stream.IntStream.range(0, candidateCount)
                .mapToObj(index -> "candidate-" + index)
                .toList();
            List<String> selected = random.nextBoolean()
                ? List.of("candidate-" + random.nextInt(candidateCount), "invented")
                : List.of();
            List<String> rejected = random.nextBoolean()
                ? List.of("candidate-" + random.nextInt(candidateCount))
                : List.of();

            SemanticCandidateAdmissionPolicy.Decision decision = policy.decide(
                candidates, selected, rejected, random.nextInt(25) == 0);
            Set<String> authorized = candidates.stream()
                .map(value -> value.toLowerCase(Locale.ROOT)).collect(Collectors.toSet());
            Set<String> denied = rejected.stream()
                .map(value -> value.toLowerCase(Locale.ROOT)).collect(Collectors.toSet());

            assertThat(decision.selectedIds()).allSatisfy(value -> {
                assertThat(authorized).contains(value.toLowerCase(Locale.ROOT));
                assertThat(denied).doesNotContain(value.toLowerCase(Locale.ROOT));
            });
            if (!decision.decided()) {
                assertThat(decision.selectedIds()).isEmpty();
            }
        }
    }
}
