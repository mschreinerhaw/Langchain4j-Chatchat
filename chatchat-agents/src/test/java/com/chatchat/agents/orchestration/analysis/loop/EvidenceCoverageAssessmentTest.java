package com.chatchat.agents.orchestration.analysis.loop;

import com.chatchat.agents.assessment.EvidenceGrade;
import com.chatchat.agents.assessment.TaskContract;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class EvidenceCoverageAssessmentTest {

    private final EvidenceCoverageAssessment assessment = new EvidenceCoverageAssessment();

    @Test
    void gradesMixedMultiSourceResultsAsPartialUsableInsteadOfAllOrNothing() {
        List<Map<String, Object>> evidence = java.util.stream.IntStream.range(0, 7)
            .mapToObj(index -> index < 5
                ? Map.<String, Object>of("stepId", index, "tool", "source-" + index,
                    "success", true, "outputFacts", List.of("fact-" + index))
                : Map.<String, Object>of("stepId", index, "tool", "source-" + index,
                    "success", false, "output", Map.of()))
            .toList();

        var result = assessment.assess(List.of(Map.of(
            "toolEvidence", evidence,
            "remainingMissing", List.of("source-5", "source-6"))), Map.of());

        assertThat(result.grade()).isEqualTo(EvidenceGrade.PARTIAL_USABLE);
        assertThat(result.evidenceAvailable()).isTrue();
        assertThat(result.usableEvidenceCount()).isEqualTo(5);
    }

    @Test
    void missingRequiredItemLimitsButDoesNotDiscardAvailableEvidence() {
        TaskContract contract = contract(List.of(
            new TaskContract.EvidenceItem("primary", 1, "source-a",
                TaskContract.EvidenceImportance.REQUIRED),
            new TaskContract.EvidenceItem("secondary", 2, "source-b",
                TaskContract.EvidenceImportance.IMPORTANT)));

        var result = assessment.assess(List.of(Map.of("toolEvidence", List.of(
            Map.of("stepId", 2, "tool", "source-b", "output", Map.of("value", 42))))),
            Map.of("taskContract", contract));

        assertThat(result.grade()).isEqualTo(EvidenceGrade.LIMITED);
        assertThat(result.grade().synthesisAllowed()).isTrue();
        assertThat(result.missingRequiredEvidenceItems()).containsExactly("primary");
    }

    @Test
    void usesSuccessfulEarlierIterationWhenLastRetryIsEmpty() {
        TaskContract contract = contract(List.of(new TaskContract.EvidenceItem(
            "primary", 1, "source-a", TaskContract.EvidenceImportance.REQUIRED)));

        var result = assessment.assess(List.of(
            Map.of("toolEvidence", List.of(Map.of("stepId", 1, "tool", "source-a",
                "outputFacts", List.of("observed")))),
            Map.of("toolEvidence", List.of(), "remainingMissing", List.of("optional follow-up"))),
            Map.of("taskContract", contract));

        assertThat(result.evidenceAvailable()).isTrue();
        assertThat(result.requiredEvidenceMissing()).isFalse();
        assertThat(result.grade()).isEqualTo(EvidenceGrade.PARTIAL_USABLE);
    }

    @Test
    void allMissingRequiredEvidenceRemainsInsufficient() {
        TaskContract contract = contract(List.of(new TaskContract.EvidenceItem(
            "primary", 1, "source-a", TaskContract.EvidenceImportance.REQUIRED)));

        var result = assessment.assess(List.of(Map.of("toolEvidence", List.of())),
            Map.of("taskContract", contract));

        assertThat(result.grade()).isEqualTo(EvidenceGrade.INSUFFICIENT);
        assertThat(result.requiredEvidenceMissing()).isTrue();
        assertThat(result.grade().synthesisAllowed()).isFalse();
    }

    private TaskContract contract(List<TaskContract.EvidenceItem> evidenceItems) {
        return new TaskContract(TaskContract.CONTRACT_VERSION, "analysis", "goal",
            TaskContract.EvidenceRequirement.REQUIRED, false, "answer", List.of(), evidenceItems);
    }
}
