package com.chatchat.agents.orchestration;

import com.chatchat.agents.runtime.AgentRunEvent;
import com.chatchat.agents.runtime.AgentRunEventType;
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
