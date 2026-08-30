package com.chatchat.agents.orchestration.evidence;

import com.chatchat.common.interaction.InteractionToolTrace;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class ReviewedPlanTraceProjectorTest {

    @Test
    void projectsReviewedOutputAndSemanticCommitMetadata() {
        InteractionToolTrace raw = InteractionToolTrace.builder()
            .toolName("template_query")
            .success(true)
            .input(Map.of("keywords", List.of("trade")))
            .output("{\"templates\":[\"selected\",\"rejected\"]}")
            .runtimeMetadata(Map.of("attempt", 1))
            .build();

        InteractionToolTrace projected = ReviewedPlanTraceProjector.project(
            raw,
            Map.of("templates", List.of("selected")),
            Map.of(
                "semanticCandidateReviewSatisfied", true,
                "selectedTemplateIds", List.of("selected")),
            new ObjectMapper());

        assertThat(projected).isNotSameAs(raw);
        assertThat(projected.getOutput()).contains("selected").doesNotContain("rejected");
        assertThat(projected.getRuntimeMetadata())
            .containsEntry("attempt", 1)
            .containsEntry("semanticCandidateReviewSatisfied", true)
            .containsEntry("reviewedPlanEvidence", true)
            .containsEntry("selectedTemplateIds", List.of("selected"));
    }

    @Test
    void leavesUnreviewedTraceUntouched() {
        InteractionToolTrace raw = InteractionToolTrace.builder()
            .toolName("generic_tool").success(true).output("raw").build();

        assertThat(ReviewedPlanTraceProjector.project(
            raw, Map.of("value", 1), Map.of(), new ObjectMapper())).isSameAs(raw);
    }
}
