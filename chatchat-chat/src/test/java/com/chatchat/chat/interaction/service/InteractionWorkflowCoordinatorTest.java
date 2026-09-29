package com.chatchat.chat.interaction.service;

import com.chatchat.chat.asset.AssetGuidanceInteractionBridge;
import com.chatchat.chat.interaction.model.*;
import com.chatchat.chat.skills.model.SkillDefinition;
import com.chatchat.common.runtime.capability.*;
import java.util.List;
import java.util.Map;
import java.util.concurrent.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class InteractionWorkflowCoordinatorTest {
    @Test @Timeout(10)
    void nativePlannerOwnsItsChildUntilItReturnsAndUsesCommonCompletion() throws Exception {
        var policy = mock(AgentToolPolicyResolver.class);
        var frontPlanner = mock(ProblemAnalysisPlanner.class);
        var direct = mock(DirectAnswerWorkflow.class);
        var assets = mock(AssetGuidanceInteractionBridge.class);
        var agent = mock(SkillDefinition.class);
        when(agent.boundMcpToolNames()).thenReturn(List.of("opaque"));
        var request = InteractionRequest.builder().query("analyze").build();
        var entry = new WorkflowEntryPlan(WorkflowEntryPlan.Owner.GOVERNED_RUNTIME, "DECLARED_EXECUTION_CONTRACT",
            List.of(Map.of("tool", "opaque", "data_type", "TEMPLATE_QUERY")));
        when(policy.planningSnapshot(request, agent)).thenReturn(entry);
        var coordinator = new InteractionWorkflowCoordinator(policy, frontPlanner, direct, assets);
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var executor = Executors.newSingleThreadExecutor();
        try {
            var result = executor.submit(() -> coordinator.execute(request, InteractionContext.builder().build(), agent,
                (context, understanding) -> {
                    assertThat(understanding).isNull();
                    entered.countDown();
                    try { release.await(); }
                    catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); throw new CancellationException(); }
                    return InteractionResponse.builder().answer("verified report")
                        .metadata(Map.of("agent", Map.of("publicStatus", "SUCCESS"))).build();
                }));
            assertThat(entered.await(3, TimeUnit.SECONDS)).isTrue();
            assertThatThrownBy(() -> result.get(150, TimeUnit.MILLISECONDS)).isInstanceOf(TimeoutException.class);
            verifyNoInteractions(frontPlanner, direct, assets);
            release.countDown();
            var response = result.get(3, TimeUnit.SECONDS);
            assertThat(response.getMetadata()).containsEntry("workflowEntryPlan", entry)
                .containsEntry("runtimeLifecycle", List.of("SELECT_PLANNING_OWNER", "GOVERNED_RUNTIME", "EVALUATE", "COMPLETE"))
                .doesNotContainKeys("problemAnalysisPlan", "capabilityPlan");
            assertThat(((WorkflowOutcome) response.getMetadata().get(WorkflowOutcome.METADATA_KEY)).publicStatus()).isEqualTo("SUCCESS");
            verify(policy, times(1)).planningSnapshot(request, agent);
            verifyNoMoreInteractions(policy);
        } finally { release.countDown(); executor.shutdownNow(); }
    }

    @Test void semanticPlannerReceivesTheEntrySnapshotAndFailureNeverStartsAnotherPlanner() {
        var policy = mock(AgentToolPolicyResolver.class);
        var planner = mock(ProblemAnalysisPlanner.class);
        var direct = mock(DirectAnswerWorkflow.class);
        var assets = mock(AssetGuidanceInteractionBridge.class);
        var governed = mock(InteractionWorkflowCoordinator.GovernedExecution.class);
        var agent = mock(SkillDefinition.class);
        when(agent.boundMcpToolNames()).thenReturn(List.of("opaque"));
        var request = InteractionRequest.builder().query("explain").build();
        var context = InteractionContext.builder().build();
        var entry = new WorkflowEntryPlan(WorkflowEntryPlan.Owner.PROBLEM_ANALYSIS, "SEMANTIC_WORKFLOW_SELECTION",
            List.of(Map.of("tool", "opaque", "data_type", "ASSET_QUERY")));
        when(policy.planningSnapshot(request, agent)).thenReturn(entry);
        when(planner.analyze(request, context, agent, entry.toolPurposes())).thenReturn(ProblemAnalysisPlan.unavailable());
        var response = new InteractionWorkflowCoordinator(policy, planner, direct, assets).execute(request, context, agent, governed);
        assertThat(((WorkflowOutcome) response.getMetadata().get(WorkflowOutcome.METADATA_KEY)).type()).isEqualTo(WorkflowOutcome.Type.FAILED);
        assertThat(response.getMetadata()).containsEntry("runtimeLifecycle",
            List.of("SELECT_PLANNING_OWNER", "PROBLEM_ANALYSIS_PLAN", "EVALUATE", "COMPLETE"));
        verify(planner).analyze(request, context, agent, entry.toolPurposes());
        verify(policy).planningSnapshot(request, agent);
        verifyNoMoreInteractions(policy, planner);
        verifyNoInteractions(direct, assets, governed);
    }
}
