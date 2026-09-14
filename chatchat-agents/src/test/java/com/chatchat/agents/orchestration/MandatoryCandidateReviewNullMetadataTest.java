package com.chatchat.agents.orchestration;

import com.chatchat.agents.runtime.plan.InterpretationPlanRuntime;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class MandatoryCandidateReviewNullMetadataTest {
    @Test void successfulCandidateReviewDropsNullAuditValuesAtRuntimeBoundary() {
        Map<String, Object> modelMetadata = new LinkedHashMap<>();
        modelMetadata.put("coverageDecision", "SUFFICIENT");
        modelMetadata.put("optionalModelField", null);

        InterpretationPlanRuntime.StepReview stepReview =
            InterpretationPlanRuntime.StepReview.accepted("covered", modelMetadata);
        AgentOrchestrationEngine.MandatoryCandidateReview candidateReview =
            AgentOrchestrationEngine.MandatoryCandidateReview.accepted(
                stepReview.reason(), Map.of("templates", java.util.List.of()), stepReview.metadata());

        assertThat(stepReview.metadata()).containsEntry("coverageDecision", "SUFFICIENT")
            .doesNotContainKey("optionalModelField");
        assertThat(candidateReview.satisfied()).isTrue();
        assertThat(candidateReview.auditMetadata()).containsEntry("coverageDecision", "SUFFICIENT")
            .doesNotContainKey("optionalModelField");
    }

    @Test void mandatoryBoundaryAlsoDefendsAgainstNullableThirdPartyMaps() {
        Map<String, Object> nullable = new LinkedHashMap<>();
        nullable.put("selectedCount", 5);
        nullable.put("modelOptional", null);

        AgentOrchestrationEngine.MandatoryCandidateReview review =
            AgentOrchestrationEngine.MandatoryCandidateReview.accepted("accepted", Map.of(), nullable);

        assertThat(review.auditMetadata()).containsExactly(Map.entry("selectedCount", 5));
    }
}
