package com.chatchat.agents.orchestration;

import com.chatchat.agents.runtime.AgentRunEvent;
import com.chatchat.agents.runtime.AgentRunEventType;
import com.chatchat.common.interaction.InteractionToolTrace;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class AgentWorkflowStateTrackerTest {

    @Test
    void onlySuccessfulTerminalObservationsBecomeCompletedWorkflowTools() {
        AgentWorkflowStateTracker tracker = new AgentWorkflowStateTracker();
        List<AgentRunEvent> events = List.of(
            observation("database_asset_search", true, 1),
            observation("database_ops_template_search", false, 2)
        );

        AgentWorkflowStateTracker.WorkflowEventSnapshot snapshot = tracker.eventSnapshot(events);

        assertThat(snapshot.completedTools()).containsExactly("database_asset_search");
        assertThat(snapshot.completedStepIds()).containsExactly(1);
        assertThat(snapshot.failedStepIds()).containsExactly(2);
    }

    @Test
    void completedToolAttributesMergeAllProtocolFormsWithoutDuplicates() {
        AgentWorkflowStateTracker tracker = new AgentWorkflowStateTracker();

        assertThat(tracker.attributesWithCompletedTools(
            Map.of("workflowCompletedTools", List.of("asset_discovery", "template_discovery")),
            java.util.Set.of("template_discovery", "query_execute")
        ).get("workflowCompletedTools")).isEqualTo(
            List.of("asset_discovery", "template_discovery", "query_execute"));

        assertThat(tracker.attributesWithCompletedTools(
            Map.of("workflowCompletedTools", "asset_discovery; template_discovery"),
            java.util.Set.of()
        ).get("workflowCompletedTools")).isEqualTo(
            List.of("asset_discovery", "template_discovery"));
    }

    @Test
    void terminalTraceStateExcludesConfirmationButPreservesFailedAttemptSemantics() {
        AgentWorkflowStateTracker tracker = new AgentWorkflowStateTracker();
        InteractionToolTrace confirmation = InteractionToolTrace.builder()
            .toolName("approval_pending")
            .success(false)
            .runtimeMetadata(Map.of("outcome", "confirmation_required"))
            .build();

        assertThat(tracker.completedToolsFromTraces(List.of(
            InteractionToolTrace.builder().toolName("successful_step").success(true).build(),
            InteractionToolTrace.builder().toolName("failed_terminal_step").success(false).build(),
            confirmation
        ))).containsExactly("successful_step", "failed_terminal_step");
    }

    private AgentRunEvent observation(String toolName, boolean success, int stepId) {
        return AgentRunEvent.of(
            "run-1",
            AgentRunEventType.OBSERVATION_RECORDED,
            "tool observation",
            Map.of("metadata", Map.of(
                "success", success,
                "toolName", toolName,
                "interpretationPlanStepId", stepId
            ))
        );
    }
}
