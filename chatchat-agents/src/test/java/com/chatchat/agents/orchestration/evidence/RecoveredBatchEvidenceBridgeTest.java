package com.chatchat.agents.orchestration.evidence;

import com.chatchat.agents.runtime.batch.ToolCallBatchResult;
import com.chatchat.agents.runtime.batch.ToolCallResult;
import com.chatchat.agents.runtime.plan.InterpretationPlanRuntime;
import com.chatchat.common.interaction.InteractionToolTrace;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class RecoveredBatchEvidenceBridgeTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void projectsSuccessfulMandatoryRecoveryBatchBackIntoExecutionEvidence() throws Exception {
        ToolCallResult child = new ToolCallResult(
            "run-1", "batch-1", "reviewed-template-1", null,
            "api_template_execute", "api_template_execute", "customer-assets", null,
            null, null, null, 1, true, "SUCCESS", true, 12L, "tool:assets",
            Map.of("records", List.of(Map.of("KHH", "070200046604", "ZZC", 847174.25))),
            null);
        ToolCallBatchResult batch = new ToolCallBatchResult(
            "batch-1", "SEQUENTIAL", "start", "end", "SUCCESS",
            new ToolCallBatchResult.Summary(1, 1, 0, 0, 0, 1), List.of(child));
        InteractionToolTrace trace = InteractionToolTrace.builder()
            .toolName("api_template_execute")
            .success(true)
            .durationMs(20L)
            .output(objectMapper.writeValueAsString(batch))
            .build();

        List<InterpretationPlanRuntime.ExecutionResult> projected =
            RecoveredBatchEvidenceBridge.project(List.of(trace), objectMapper);

        assertThat(projected).singleElement().satisfies(result -> {
            assertThat(result.status()).isEqualTo("RECOVERED_BATCH_EVIDENCE");
            assertThat(result.steps()).singleElement().satisfies(step -> {
                assertThat(step.output()).isInstanceOf(ToolCallBatchResult.class);
                ToolCallBatchResult restored = (ToolCallBatchResult) step.output();
                assertThat(restored.results()).singleElement().satisfies(restoredChild ->
                    assertThat(restoredChild.output().toString()).contains("070200046604", "847174.25"));
            });
        });
    }

    @Test
    void ignoresFailedAndNonBatchTraces() {
        InteractionToolTrace failed = InteractionToolTrace.builder()
            .toolName("api_template_execute").success(false).output("{}").build();
        InteractionToolTrace ordinary = InteractionToolTrace.builder()
            .toolName("document_search").success(true).output("{\"records\":[]}").build();

        assertThat(RecoveredBatchEvidenceBridge.project(
            List.of(failed, ordinary), objectMapper)).isEmpty();
    }
}
