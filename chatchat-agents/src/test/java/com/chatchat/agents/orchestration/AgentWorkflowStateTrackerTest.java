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
    void onlySuccessfulMcpTraceStatusCountsAsCompleted() {
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
        ))).containsExactly("successful_step");
    }

    @Test
    void observationWithoutExplicitSuccessfulMcpStatusIsNotExecuted() {
        AgentWorkflowStateTracker tracker = new AgentWorkflowStateTracker();
        AgentRunEvent missingStatus = AgentRunEvent.of(
            "run-1",
            AgentRunEventType.OBSERVATION_RECORDED,
            "incomplete observation",
            Map.of("metadata", Map.of(
                "structuredRuntimeObservation", true,
                "toolName", "api_asset_query",
                "interpretationPlanStepId", 1
            ))
        );

        AgentWorkflowStateTracker.WorkflowEventSnapshot snapshot =
            tracker.eventSnapshot(List.of(missingStatus));

        assertThat(snapshot.completedTools()).isEmpty();
        assertThat(snapshot.completedStepIds()).isEmpty();
    }

    @Test
    void carriesTheUniqueSuccessfulAssetTargetAcrossWorkflowAttempts() {
        AgentWorkflowStateTracker tracker = new AgentWorkflowStateTracker();
        InteractionToolTrace asset = InteractionToolTrace.builder()
            .toolName("mcp_generated_ssh_asset_query")
            .success(true)
            .input(Map.of("filters", Map.of("assetName", "worker-generated")))
            .build();

        Map<String, Object> attributes = tracker.attributesWithCompletedWorkflowState(
            Map.of(), java.util.Set.of(asset.getToolName()), List.of(asset));

        assertThat(attributes.get("workflowContext"))
            .isEqualTo(Map.of("workflowTargetRef", "worker-generated"));
    }

    @Test
    void doesNotGuessWorkflowTargetAcrossFailedOrAmbiguousAssetQueries() {
        AgentWorkflowStateTracker tracker = new AgentWorkflowStateTracker();
        InteractionToolTrace first = InteractionToolTrace.builder()
            .toolName("mcp_generated_ssh_asset_query")
            .success(true)
            .input(Map.of("filters", Map.of("assetName", "worker-a")))
            .build();
        InteractionToolTrace second = InteractionToolTrace.builder()
            .toolName("mcp_generated_ssh_asset_query")
            .success(true)
            .input(Map.of("filters", Map.of("assetName", "worker-b")))
            .build();
        InteractionToolTrace failed = InteractionToolTrace.builder()
            .toolName("mcp_generated_ssh_asset_query")
            .success(false)
            .input(Map.of("filters", Map.of("assetName", "worker-failed")))
            .build();

        assertThat(tracker.attributesWithCompletedWorkflowState(
            Map.of(), java.util.Set.of(first.getToolName()), List.of(first, second, failed)))
            .doesNotContainKey("workflowContext");
        assertThat(tracker.attributesWithCompletedWorkflowState(
            Map.of("workflowContext", Map.of("workflowTargetRef", "explicit-target")),
            java.util.Set.of(first.getToolName()), List.of(first)))
            .containsEntry("workflowContext", Map.of("workflowTargetRef", "explicit-target"));
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
